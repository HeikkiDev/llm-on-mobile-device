import AVFAudio
import Foundation
import Synchronization

// Gemma 4 audio input: 16 kHz mono, at most 30 s (model card). SPEC-WALTER §4.1.
let audioSampleRate = 16_000
let maxRecordingMs = 30_000
let warnRecordingMs = 25_000
let silenceToStopMs = 2_500
let noSpeechTimeoutMs = 8_000
let frameMs = 30
let frameSamples = audioSampleRate * frameMs / 1000

// VAD tuning (16-bit RMS). Revisit on site (SPEC-WALTER W5).
private let speechFactor = 3.0
private let minSpeechRms = 250.0
private let speechFramesToStart = 3
private let paddingMs = 300
private let minManualMs = 500

enum Recording: Equatable {
    case speech(wav: Data, durationMs: Int)
    case noSpeech
}

enum GateEvent { case `continue`, stop, noSpeech }

/// Energy-based voice activity detection over 30 ms frames. The noise floor follows the quietest
/// recent frames; "speech" is a few consecutive frames well above it. Pure, so it is unit-tested.
struct SpeechGate {
    private(set) var frames = 0
    private(set) var firstSpeechFrame = -1
    private(set) var lastSpeechFrame = -1
    private var floor = -1.0
    private var loudRun = 0

    var heardSpeech: Bool { firstSpeechFrame >= 0 }

    mutating func accept(_ rms: Double) -> GateEvent {
        frames += 1
        if floor < 0 || rms < floor { floor = rms }
        let loud = rms > max(floor * speechFactor, minSpeechRms)
        // Adapt upwards only on quiet frames, so long speech never raises the floor above itself.
        if !loud { floor += (rms - floor) * 0.05 }
        loudRun = loud ? loudRun + 1 : 0
        if loudRun >= speechFramesToStart {
            if firstSpeechFrame < 0 { firstSpeechFrame = frames - speechFramesToStart }
            lastSpeechFrame = frames - 1
        }
        let elapsedMs = frames * frameMs
        if elapsedMs >= maxRecordingMs { return heardSpeech ? .stop : .noSpeech }
        if !heardSpeech && elapsedMs >= noSpeechTimeoutMs { return .noSpeech }
        if heardSpeech && (frames - 1 - lastSpeechFrame) * frameMs >= silenceToStopMs { return .stop }
        return .continue
    }

    /// Sample range to keep: speech plus a little padding, or everything if speech was never detected.
    func keptRange(totalSamples: Int) -> Range<Int> {
        guard heardSpeech else { return 0..<totalSamples }
        let pad = paddingMs / frameMs
        let start = min(totalSamples, max(0, firstSpeechFrame - pad) * frameSamples)
        let end = min(totalSamples, (lastSpeechFrame + 1 + pad) * frameSamples)
        return start..<max(start, end)
    }

    /// The turn's result once recording ends: trimmed speech as WAV, or `.noSpeech`.
    func recording(from samples: [Int16]) -> Recording {
        if !heardSpeech && samples.count * 1000 / audioSampleRate < minManualMs { return .noSpeech }
        let kept = samples[keptRange(totalSamples: samples.count)]
        return .speech(wav: wav(kept), durationMs: kept.count * 1000 / audioSampleRate)
    }
}

func rms(_ frame: ArraySlice<Int16>) -> Double {
    guard !frame.isEmpty else { return 0 }
    let sum = frame.reduce(0.0) { $0 + Double($1) * Double($1) }
    return (sum / Double(frame.count)).squareRoot()
}

/// 16-bit PCM mono WAV (44-byte header, little endian).
func wav(_ samples: ArraySlice<Int16>, sampleRate: Int = audioSampleRate) -> Data {
    var out = Data(capacity: 44 + samples.count * 2)
    func int32(_ v: Int) { withUnsafeBytes(of: UInt32(v).littleEndian) { out.append(contentsOf: $0) } }
    func int16(_ v: Int) { withUnsafeBytes(of: UInt16(v).littleEndian) { out.append(contentsOf: $0) } }
    let dataBytes = samples.count * 2
    out.append(contentsOf: Array("RIFF".utf8)); int32(36 + dataBytes); out.append(contentsOf: Array("WAVE".utf8))
    out.append(contentsOf: Array("fmt ".utf8)); int32(16); int16(1); int16(1)
    int32(sampleRate); int32(sampleRate * 2); int16(2); int16(16)
    out.append(contentsOf: Array("data".utf8)); int32(dataBytes)
    for sample in samples { withUnsafeBytes(of: sample.littleEndian) { out.append(contentsOf: $0) } }
    return out
}

// MARK: - Microphone

struct MicrophoneError: LocalizedError {
    var errorDescription: String? { "No se puede usar el micrófono." }
}

/// Records one turn from the microphone: AVAudioEngine input tap → 16 kHz mono Int16 → VAD → WAV in memory.
/// The audio is never written to disk.
@MainActor
final class AudioRecorder {
    private var capture: Capture?

    /// Asks for the microphone the first time (SPEC-WALTER §4.1: on the first mic tap, never at launch).
    static func requestPermission() async -> Bool {
        switch AVAudioApplication.shared.recordPermission {
        case .granted: true
        case .denied: false
        default: await AVAudioApplication.requestRecordPermission()
        }
    }

    /// Loudspeaker output for the voices, microphone input for the turns.
    static func configureSession() {
        let session = AVAudioSession.sharedInstance()
        try? session.setCategory(.playAndRecord, mode: .default, options: [.defaultToSpeaker, .allowBluetoothHFP])
        try? session.setActive(true)
    }

    static var outputVolume: Float { AVAudioSession.sharedInstance().outputVolume }

    /// Tap-to-stop: ends the current recording and keeps what was said.
    func requestStop() {
        capture?.finish()
    }

    func record(onLevel: @escaping @MainActor @Sendable (_ level: Float, _ elapsedMs: Int) -> Void) async throws -> Recording {
        Self.configureSession()
        let engine = AVAudioEngine()
        let input = engine.inputNode
        let format = input.outputFormat(forBus: 0)
        guard format.sampleRate > 0, format.channelCount > 0,
              let target = AVAudioFormat(commonFormat: .pcmFormatInt16, sampleRate: Double(audioSampleRate),
                                         channels: 1, interleaved: true),
              let converter = AVAudioConverter(from: format, to: target)
        else { throw MicrophoneError() }
        let capture = Capture(converter: converter, target: target, onLevel: onLevel)
        self.capture = capture
        defer {
            input.removeTap(onBus: 0)
            engine.stop()
            self.capture = nil
        }
        capture.install(on: input, format: format)
        engine.prepare()
        do {
            try engine.start()
        } catch {
            throw MicrophoneError()
        }
        return try await withTaskCancellationHandler {
            try await capture.result()
        } onCancel: {
            capture.cancel()
        }
    }
}

/// One recording session. `process` runs on the audio tap's thread; everything shared is behind the mutex.
private final class Capture: @unchecked Sendable {
    private struct State {
        var gate = SpeechGate()
        var samples: [Int16] = []
        var pending: [Int16] = []
        var outcome: Result<Recording, Error>?
        var continuation: CheckedContinuation<Recording, Error>?
    }

    private let converter: AVAudioConverter
    private let target: AVAudioFormat
    private let onLevel: @MainActor @Sendable (Float, Int) -> Void
    private let state = Mutex(State())

    init(converter: AVAudioConverter, target: AVAudioFormat, onLevel: @escaping @MainActor @Sendable (Float, Int) -> Void) {
        self.converter = converter
        self.target = target
        self.onLevel = onLevel
    }

    func result() async throws -> Recording {
        try await withCheckedThrowingContinuation { continuation in
            let outcome: Result<Recording, Error>? = state.withLock { state in
                if state.outcome == nil { state.continuation = continuation }
                return state.outcome
            }
            if let outcome { continuation.resume(with: outcome) }
        }
    }

    func cancel() {
        complete(.failure(CancellationError()))
    }

    /// Ends the recording with what was heard so far (tap-to-stop or the VAD's decision).
    func finish(noSpeech: Bool = false) {
        let recording: Recording? = state.withLock { state in
            guard state.outcome == nil else { return nil }
            return noSpeech ? .noSpeech : state.gate.recording(from: state.samples)
        }
        if let recording { complete(.success(recording)) }
    }

    private func complete(_ outcome: Result<Recording, Error>) {
        let continuation: CheckedContinuation<Recording, Error>? = state.withLock { state in
            guard state.outcome == nil else { return nil }
            state.outcome = outcome
            let continuation = state.continuation
            state.continuation = nil
            return continuation
        }
        continuation?.resume(with: outcome)
    }

    /// Installed from this nonisolated class so the tap block isn't main-actor isolated (it runs on an audio thread).
    func install(on input: AVAudioInputNode, format: AVAudioFormat) {
        input.installTap(onBus: 0, bufferSize: 4096, format: format) { buffer, _ in
            self.process(buffer)
        }
    }

    func process(_ buffer: AVAudioPCMBuffer) {
        guard let samples = convert(buffer), !samples.isEmpty else { return }
        var event = GateEvent.continue
        var level: (Float, Int)?
        state.withLock { state in
            guard state.outcome == nil else { return }
            state.pending.append(contentsOf: samples)
            while event == .continue, state.pending.count >= frameSamples {
                let frame = state.pending[..<frameSamples]
                let room = audioSampleRate * maxRecordingMs / 1000 - state.samples.count
                state.samples.append(contentsOf: frame.prefix(max(0, room)))
                let value = rms(frame)
                event = state.gate.accept(value)
                level = (Float(min(1, value / 4000)), state.gate.frames * frameMs)
                state.pending.removeFirst(frameSamples)
            }
        }
        if case let (value, elapsed)? = level {
            let onLevel = onLevel
            Task { @MainActor in onLevel(value, elapsed) }
        }
        switch event {
        case .continue: break
        case .stop: finish()
        case .noSpeech: finish(noSpeech: true)
        }
    }

    /// Converts one tap buffer (hardware rate, Float32) to 16 kHz mono Int16. Runs only on the tap's thread.
    private func convert(_ buffer: AVAudioPCMBuffer) -> [Int16]? {
        let ratio = target.sampleRate / buffer.format.sampleRate
        let capacity = AVAudioFrameCount((Double(buffer.frameLength) * ratio).rounded(.up)) + 32
        guard let out = AVAudioPCMBuffer(pcmFormat: target, frameCapacity: capacity) else { return nil }
        let feed = Feed(buffer)
        var error: NSError?
        let status = converter.convert(to: out, error: &error) { _, inputStatus in
            guard let next = feed.take() else {
                inputStatus.pointee = .noDataNow
                return nil
            }
            inputStatus.pointee = .haveData
            return next
        }
        guard status != .error, error == nil, let channel = out.int16ChannelData else { return nil }
        return Array(UnsafeBufferPointer(start: channel[0], count: Int(out.frameLength)))
    }
}

/// Hands one buffer to AVAudioConverter's input block exactly once.
private final class Feed: @unchecked Sendable {
    private var buffer: AVAudioPCMBuffer?

    init(_ buffer: AVAudioPCMBuffer) { self.buffer = buffer }

    func take() -> AVAudioPCMBuffer? {
        defer { buffer = nil }
        return buffer
    }
}
