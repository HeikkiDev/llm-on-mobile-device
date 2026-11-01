import Foundation
import LiteRTLM
import Observation
import UIKit

let maxInputChars = 1000
let lowRamWarning = "Este modelo está recomendado para móviles con 8 GB de RAM o más."
let notUnderstood = "No he entendido, repite más despacio"
let nothingHeard = "No he oído nada"
let spanishDetected = "Eso parece español. Pídele a tu compañero que diga una frase."
private let maxNumTokens = 4096
private let lowRamBytes: UInt64 = 7_500_000_000
private let coworkerSpeechRate: Float = 0.9
private let lowVolume: Float = 0.4
private let coworkerKey = "coworker"
private let recentsKey = "recents"
/// Spanish is a candidate too, so Walter speaking by mistake is recognised (§5.3).
private let detectionCandidates = Languages.all + [Languages.spanish]

enum ModelStatus: Equatable {
    /// `partialBytes`: bytes kept from an interrupted download.
    case missing(partialBytes: Int64)
    case needsCellularConsent
    case downloading(bytes: Int64)
    case verifying
    case loading
    case ready
    /// `loadFailed`: the file is present but the engine could not load it.
    case error(String, loadFailed: Bool = false)
}

enum Side {
    case walter, coworker
    var other: Side { self == .walter ? .coworker : .walter }
}

/// One bubble. `transcript` is what was said (or typed); `translation` is shown large and spoken.
struct Turn: Identifiable, Equatable {
    let id: Int
    var side: Side
    var source: Language
    var target: Language
    var transcript = ""
    var translation = ""
    var done = false
    var error: String?
    var typed = false
}

/// What the app is doing right now; only one turn at a time (SPEC-WALTER §5.2).
enum Activity: Equatable {
    case idle
    case listening(Side, level: Float = 0, elapsedMs: Int = 0, detecting: Bool = false)
    case translating(Side)
    case detecting
    case speaking(turnId: Int)
}

enum DetectSheet { case ask, failed }

private enum Request {
    case audio(Side, Data)
    case text(String)
}

private struct Runaway: Error {}

/// The only state holder of the app: model download, engine, conversation, recorder, speaker and settings.
@Observable @MainActor
final class TranslatorModel {
    static let backend: Backend = .cpu()
    let lowRam = ProcessInfo.processInfo.physicalMemory < lowRamBytes

    private(set) var status: ModelStatus = .missing(partialBytes: 0) {
        didSet {
            let busy = status == .verifying || { if case .downloading = status { true } else { false } }()
            UIApplication.shared.isIdleTimerDisabled = busy
        }
    }
    private(set) var coworker: Language?
    private(set) var recents: [Language] = []
    private(set) var turns: [Turn] = []
    private(set) var activity: Activity = .idle
    /// The side whose button pulses: it is their turn to speak.
    private(set) var hint: Side?
    private(set) var detectSheet: DetectSheet?
    /// Just detected; the banner asks "¿Correcto?".
    private(set) var confirmLanguage: Language?
    /// Codes of languages (including "es") without a voice on this phone.
    private(set) var voiceless: Set<String> = []
    private(set) var lowVolumeNotice = false
    /// Short notice shown once.
    var message: String?
    var micDenied = false

    @ObservationIgnored private let downloader = ModelDownloader()
    @ObservationIgnored private let recorder = AudioRecorder()
    @ObservationIgnored private let speaker = Speaker()
    @ObservationIgnored private var engine: Engine?
    @ObservationIgnored private var engineLoad: Task<Engine, Error>?
    @ObservationIgnored private var conversation: Conversation?
    @ObservationIgnored private var downloadTask: Task<Void, Never>?
    @ObservationIgnored private var turnTask: Task<Void, Never>?
    @ObservationIgnored private var stopRequested = false
    @ObservationIgnored private var cellularAccepted = false
    @ObservationIgnored private var nextTurnId = 1
    /// Kept in memory only, for "Reintentar"; replaced by the next turn (§5.2).
    @ObservationIgnored private var lastRequest: Request?

    init() {
        let defaults = UserDefaults.standard
        coworker = Languages.byCode(defaults.string(forKey: coworkerKey))
        recents = Languages.decodeRecents(defaults.string(forKey: recentsKey)).compactMap { Languages.byCode($0) }
        if downloader.isModelValid() { load() } else { showMissing() }
    }

    // MARK: Download

    func download() {
        guard downloadTask == nil else { return }
        status = .downloading(bytes: downloader.partialBytes())
        let downloader = downloader
        let allowExpensive = cellularAccepted
        downloadTask = Task {
            defer { downloadTask = nil }
            do {
                try await downloader.download(
                    allowExpensive: allowExpensive,
                    onProgress: { bytes in Task { @MainActor in self.progress(bytes) } },
                    onVerifying: { Task { @MainActor in self.status = .verifying } }
                )
                load()
            } catch is CancellationError {
                showMissing()
            } catch DownloadError.expensiveNetwork {
                status = .needsCellularConsent
            } catch {
                status = .error(error.localizedDescription)
            }
        }
    }

    private func progress(_ bytes: Int64) {
        if case .downloading = status { status = .downloading(bytes: bytes) }
    }

    func acceptCellular() {
        cellularAccepted = true
        download()
    }

    func waitForWifi() { showMissing() }

    /// Pauses the download; the next `download()` resumes it.
    func cancelDownload() { downloader.cancel() }

    func deleteModelAndDownloadAgain() {
        engine = nil
        downloader.deleteModel()
        showMissing()
    }

    private func showMissing() { status = .missing(partialBytes: downloader.partialBytes()) }

    // MARK: Engine

    private func load() {
        status = .loading
        Task {
            do {
                _ = try await loadedEngine()
                status = .ready
            } catch {
                status = .error("No se ha podido cargar el modelo.", loadFailed: true)
            }
        }
    }

    /// Returns the engine, loading it if needed (also after `releaseIfIdle()`).
    private func loadedEngine() async throws -> Engine {
        if let engine { return engine }
        if let engineLoad { return try await engineLoad.value }
        let config = try EngineConfig(
            modelPath: downloader.modelURL.path,
            backend: Self.backend,
            audioBackend: .cpu(),
            maxNumTokens: maxNumTokens,
            cacheDir: FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0].path
        )
        let load = Task {
            let engine = Engine(engineConfig: config)
            try await engine.initialize() // runs on the Engine actor, off the main thread
            return engine
        }
        engineLoad = load
        defer { engineLoad = nil }
        let engine = try await load.value
        self.engine = engine
        return engine
    }

    /// Frees the engine's memory under pressure; the next turn reloads it.
    func releaseIfIdle() {
        guard !isBusy, engineLoad == nil else { return }
        conversation = nil
        engine = nil
    }

    /// Translating or detecting: the engine is in use.
    var isBusy: Bool {
        switch activity {
        case .translating, .detecting: true
        default: false
        }
    }

    // MARK: Language

    func setCoworker(_ language: Language) {
        let codes = Languages.pushRecent(recents.map(\.code), language.code)
        UserDefaults.standard.set(language.code, forKey: coworkerKey)
        UserDefaults.standard.set(Languages.encodeRecents(codes), forKey: recentsKey)
        coworker = language
        recents = codes.compactMap { Languages.byCode($0) }
    }

    func openDetection() {
        if activity != .idle { stopSpeaking() }
        detectSheet = .ask
        confirmLanguage = nil
    }

    func closeDetection() {
        if case .listening(_, _, _, true) = activity { turnTask?.cancel() }
        detectSheet = nil
    }

    func confirmDetected() { confirmLanguage = nil }

    // MARK: Turns

    /// A tap on "Hablo yo" / "Habla él" (or the detection mic): start, stop, or barge in (§4.1, §5.2).
    func onMic(_ side: Side, detecting: Bool = false) {
        switch activity {
        case .listening(let listeningSide, _, _, _):
            if listeningSide == side { recorder.requestStop() }
        case .translating(let translatingSide):
            if translatingSide == side { stop() }
        case .speaking(let turnId):
            // Barge-in: the other side answers right away; the same side's button just stops the voice.
            let speakingSide = turns.first { $0.id == turnId }?.side
            stopSpeaking()
            if speakingSide != side || detecting { listen(side, detecting: detecting) }
        case .idle:
            listen(side, detecting: detecting)
        case .detecting:
            break
        }
    }

    private func listen(_ side: Side, detecting: Bool) {
        guard status == .ready, coworker != nil || detecting else { return }
        refreshDevice()
        activity = .listening(side, detecting: detecting)
        hint = nil
        message = nil
        turnTask = Task {
            guard await AudioRecorder.requestPermission() else {
                activity = .idle
                micDenied = true
                return
            }
            let recording: Recording
            do {
                recording = try await recorder.record { level, elapsedMs in
                    guard case .listening(let side, _, _, let detecting) = self.activity else { return }
                    self.activity = .listening(side, level: level, elapsedMs: elapsedMs, detecting: detecting)
                }
            } catch is CancellationError {
                activity = .idle
                return
            } catch {
                activity = .idle
                message = error.localizedDescription
                return
            }
            switch recording {
            case .noSpeech:
                activity = .idle
                message = nothingHeard
            case .speech(let wav, _):
                if detecting { await detect(wav) } else { await translateAudio(side, wav) }
            }
        }
    }

    private func detect(_ wav: Data) async {
        activity = .detecting
        let prompt = Message(of: .text(buildDetectionPrompt(detectionCandidates)), .audioData(wav))
        let raw = (try? await infer(system: detectionSystemInstruction, message: prompt)) ?? ""
        switch parseDetectedLanguage(raw, candidates: detectionCandidates) {
        case nil:
            activity = .idle
            detectSheet = .failed
        case .some(Languages.spanish):
            activity = .idle
            message = spanishDetected
        case let language?:
            setCoworker(language)
            detectSheet = nil
            confirmLanguage = language
            // The same clip becomes the first bubble, so detection costs no extra turn (§5.3).
            await translateAudio(.coworker, wav)
        }
    }

    private func translateAudio(_ side: Side, _ wav: Data) async {
        guard let coworker else { activity = .idle; return }
        let (source, target) = side == .walter ? (Languages.spanish, coworker) : (coworker, Languages.spanish)
        lastRequest = .audio(side, wav)
        let id = addTurn(Turn(id: nextId(), side: side, source: source, target: target), activity: .translating(side))
        let prompt = Message(of: .text(buildSpeechPrompt(source: source, target: target)), .audioData(wav))
        await runTurn(id, system: speechSystemInstruction, message: prompt) { raw, isFinal in
            let parsed = parseSpeechTranslation(raw, target: target, isFinal: isFinal)
            return (parsed.transcript, parsed.translation)
        }
    }

    /// Writing mode (§3.3 C): one prompt for both directions; the bubble side is guessed afterwards.
    func translateText(_ input: String) {
        let text = input.trimmingCharacters(in: .whitespacesAndNewlines)
        guard let coworker, status == .ready, !text.isEmpty, text.count <= maxInputChars else { return }
        if activity != .idle { stopSpeaking() }
        guard activity == .idle else { return }
        lastRequest = .text(text)
        let id = addTurn(
            Turn(id: nextId(), side: .walter, source: Languages.spanish, target: coworker, transcript: text, typed: true),
            activity: .translating(.walter)
        )
        turnTask = Task {
            await runTurn(id, system: buildTextSystemInstruction(coworker), message: Message(text)) { raw, _ in
                (text, raw.trimmingCharacters(in: .whitespacesAndNewlines))
            }
            guard let turn = turns.first(where: { $0.id == id }), turn.done else { return }
            if turn.error == nil, typedByCoworker(input: text, output: turn.translation) {
                // Typed in the coworker's language: it belongs on his side and is spoken in Spanish.
                updateTurn(id) { $0.side = .coworker; $0.source = coworker; $0.target = Languages.spanish }
            }
            if let turn = turns.first(where: { $0.id == id }), turn.error == nil { speak(turn) }
        }
    }

    func retry(_ turnId: Int) {
        guard activity == .idle, let request = lastRequest else { return }
        turns.removeAll { $0.id == turnId }
        switch request {
        case .audio(let side, let wav): turnTask = Task { await translateAudio(side, wav) }
        case .text(let text): translateText(text)
        }
    }

    /// Streams one inference into turn `id`; `split` maps the raw output to (transcript, translation).
    private func runTurn(_ id: Int, system: String, message: Message,
                         split: (_ raw: String, _ isFinal: Bool) -> (String, String)) async {
        let raw: String
        do {
            raw = try await infer(system: system, message: message) { partial in
                let (transcript, translation) = split(partial, false)
                updateTurn(id) { $0.transcript = transcript; $0.translation = translation }
            }
        } catch is Runaway {
            updateTurn(id) { $0.done = true; $0.translation = ""; $0.error = notUnderstood }
            activity = .idle
            return
        } catch {
            updateTurn(id) { $0.done = true; $0.error = "No se ha podido traducir." }
            activity = .idle
            return
        }
        if stopRequested {
            stopRequested = false
            // Stopped by the user: keep what was produced, don't speak it.
            turns = turns.compactMap { turn in
                guard turn.id == id else { return turn }
                if turn.translation.isEmpty { return nil }
                var done = turn
                done.done = true
                return done
            }
            activity = .idle
            return
        }
        let (transcript, translation) = split(raw, true)
        if translation.isEmpty {
            updateTurn(id) { $0.done = true; $0.transcript = transcript; $0.error = notUnderstood }
            activity = .idle
            return
        }
        updateTurn(id) { $0.done = true; $0.transcript = transcript; $0.translation = translation }
        guard let turn = turns.first(where: { $0.id == id }), !turn.typed else {
            activity = .idle // typed turns are spoken by translateText once their side is known
            return
        }
        speak(turn)
    }

    /// One single-shot inference; `onText` gets the accumulated output while it streams.
    private func infer(system: String, message: Message, onText: (String) -> Void = { _ in }) async throws -> String {
        stopRequested = false
        let engine = try await loadedEngine()
        // Single-shot: a fresh conversation per turn keeps history out of the prompt.
        conversation = nil
        let conversation = try await engine.createConversation(with: ConversationConfig(
            systemMessage: Message(system, role: .system),
            samplerConfig: try SamplerConfig(topK: 1, topP: 1.0, temperature: 0.0),
            thinkingConfig: ThinkingConfig(enableThinking: false)
        ))
        self.conversation = conversation
        var out = ""
        var runaway = false
        do {
            for try await chunk in conversation.sendMessageStream(message) {
                // cancel() may not interrupt prefill; drop anything that arrives after Stop.
                if stopRequested { break }
                for case .text(let piece) in chunk.contents { out += piece }
                if isRunaway(out) {
                    runaway = true
                    try? conversation.cancel()
                    break
                }
                onText(out)
            }
        } catch {
            // cancel() ends the stream with an error; that is expected after a stop.
            if !stopRequested { throw error }
        }
        if runaway { throw Runaway() }
        return out
    }

    func stop() {
        guard case .translating = activity else { return }
        stopRequested = true
        try? conversation?.cancel()
    }

    // MARK: Speaking

    func repeatTurn(_ turnId: Int) {
        switch activity {
        case .idle, .speaking: break
        default: return
        }
        if let turn = turns.first(where: { $0.id == turnId && $0.done && $0.error == nil }) { speak(turn) }
    }

    private func speak(_ turn: Turn) {
        refreshDevice()
        let rate: Float = turn.target == Languages.spanish ? 1.0 : coworkerSpeechRate
        let started = speaker.speak(turn.translation, language: turn.target, rate: rate) { [weak self] in
            guard let self, self.activity == .speaking(turnId: turn.id) else { return }
            self.activity = .idle
            self.hint = turn.side.other
        }
        if started {
            activity = .speaking(turnId: turn.id)
            hint = nil
        } else {
            activity = .idle // no voice: the screen is shown instead
            hint = turn.side.other
        }
    }

    func stopSpeaking() {
        speaker.stop()
        if case .speaking = activity { activity = .idle }
    }

    // MARK: Misc

    /// "Nueva": forget the conversation (it only ever lived in memory).
    func newConversation() {
        guard !isBusy else { return }
        stopSpeaking()
        lastRequest = nil
        turns = []
        hint = nil
        confirmLanguage = nil
    }

    func dismissLowVolume() { lowVolumeNotice = false }

    /// On launch, on returning to the app and before each turn: installed voices and volume may have changed.
    func refreshDevice() {
        AudioRecorder.configureSession()
        voiceless = speaker.voiceless(Languages.all + [Languages.spanish])
        lowVolumeNotice = AudioRecorder.outputVolume < lowVolume
    }

    private func nextId() -> Int {
        defer { nextTurnId += 1 }
        return nextTurnId
    }

    private func addTurn(_ turn: Turn, activity: Activity) -> Int {
        turns.append(turn)
        self.activity = activity
        hint = nil
        return turn.id
    }

    private func updateTurn(_ id: Int, _ change: (inout Turn) -> Void) {
        guard let index = turns.firstIndex(where: { $0.id == id }) else { return }
        change(&turns[index])
    }
}
