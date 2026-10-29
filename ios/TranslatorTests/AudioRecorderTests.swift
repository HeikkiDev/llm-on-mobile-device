import Foundation
import Testing
@testable import Translator

struct AudioRecorderTests {
    private func feed(_ gate: inout SpeechGate, rms: Double, ms: Int) -> GateEvent {
        var event = GateEvent.continue
        for _ in 0..<(ms / frameMs) {
            let next = gate.accept(rms)
            if event == .continue { event = next }
        }
        return event
    }

    @Test func silenceOnlyIsNoSpeechAfterTimeout() {
        var gate = SpeechGate()
        #expect(feed(&gate, rms: 50, ms: noSpeechTimeoutMs - 2 * frameMs) == .continue)
        #expect(feed(&gate, rms: 50, ms: 4 * frameMs) == .noSpeech)
    }

    @Test func stopsAfterSilenceWindowNotDuringShortPauses() {
        var gate = SpeechGate()
        _ = feed(&gate, rms: 50, ms: 600)
        #expect(feed(&gate, rms: 3000, ms: 2000) == .continue)
        #expect(feed(&gate, rms: 50, ms: 1500) == .continue) // a natural pause
        #expect(feed(&gate, rms: 3000, ms: 1000) == .continue)
        #expect(feed(&gate, rms: 50, ms: silenceToStopMs - 3 * frameMs) == .continue)
        #expect(feed(&gate, rms: 50, ms: 5 * frameMs) == .stop)
    }

    @Test func longSpeechNeverStopsBeforeLimit() {
        var gate = SpeechGate()
        _ = feed(&gate, rms: 50, ms: 300)
        #expect(feed(&gate, rms: 3000, ms: 20_000) == .continue)
        #expect(feed(&gate, rms: 3000, ms: maxRecordingMs) == .stop)
    }

    @Test func keepsSpeechPlusPaddingOnly() {
        var gate = SpeechGate()
        _ = feed(&gate, rms: 50, ms: 3000)
        _ = feed(&gate, rms: 3000, ms: 1500)
        _ = feed(&gate, rms: 50, ms: silenceToStopMs)
        let range = gate.keptRange(totalSamples: gate.frames * frameSamples)
        let keptMs = range.count * 1000 / audioSampleRate
        #expect((1500...2200).contains(keptMs), "kept \(keptMs) ms")
        #expect(range.lowerBound >= (3000 - 400) * audioSampleRate / 1000)
    }

    @Test func veryShortQuietTapIsNoSpeechButLongerManualStopKeepsAudio() {
        var gate = SpeechGate()
        _ = feed(&gate, rms: 50, ms: 300)
        #expect(gate.recording(from: [Int16](repeating: 0, count: gate.frames * frameSamples)) == .noSpeech)
        _ = feed(&gate, rms: 50, ms: 700)
        // A quiet speaker may stay under the VAD threshold: a manual stop still sends the audio.
        guard case .speech(_, let durationMs) = gate.recording(from: [Int16](repeating: 0, count: gate.frames * frameSamples)) else {
            Issue.record("expected speech")
            return
        }
        #expect(durationMs == gate.frames * frameMs)
    }

    @Test func wavHeaderIsPcm16Mono16k() {
        let bytes = [UInt8](wav((0..<10).map { Int16($0) }[...]))
        func int32(_ at: Int) -> Int { Int(bytes[at]) | Int(bytes[at + 1]) << 8 | Int(bytes[at + 2]) << 16 | Int(bytes[at + 3]) << 24 }
        func int16(_ at: Int) -> Int { Int(bytes[at]) | Int(bytes[at + 1]) << 8 }
        func ascii(_ at: Int, _ count: Int) -> String { String(decoding: bytes[at..<at + count], as: UTF8.self) }
        #expect(bytes.count == 44 + 20)
        #expect(ascii(0, 4) == "RIFF")
        #expect(int32(4) == 36 + 20)
        #expect(ascii(8, 8) == "WAVEfmt ")
        #expect(int32(16) == 16)
        #expect(int16(20) == 1) // PCM
        #expect(int16(22) == 1) // mono
        #expect(int32(24) == audioSampleRate)
        #expect(int32(28) == audioSampleRate * 2)
        #expect(int16(34) == 16)
        #expect(ascii(36, 4) == "data")
        #expect(int32(40) == 20)
        #expect(int16(44 + 18) == 9)
    }

    @Test func rmsOfConstantSignal() {
        #expect(abs(rms([Int16](repeating: 1000, count: frameSamples)[...]) - 1000) < 0.001)
        #expect(rms([Int16]()[...]) == 0)
    }
}
