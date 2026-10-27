import Foundation
import LiteRTLM
import Observation
import UIKit

let maxInputChars = 1000
private let maxNumTokens = 4096
private let targetKey = "target"

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

/// The only state holder of the app: model download, engine and translation.
@Observable @MainActor
final class TranslatorModel {
    static let backend: Backend = .cpu()

    private(set) var status: ModelStatus = .missing(partialBytes: 0) {
        didSet {
            let busy = status == .verifying || { if case .downloading = status { true } else { false } }()
            UIApplication.shared.isIdleTimerDisabled = busy
        }
    }
    var input = ""
    var target: Language {
        didSet { UserDefaults.standard.set(target.code, forKey: targetKey) }
    }
    private(set) var output = ""
    private(set) var translating = false
    var error: String?

    @ObservationIgnored private let downloader = ModelDownloader()
    @ObservationIgnored private var engine: Engine?
    @ObservationIgnored private var engineLoad: Task<Engine, Error>?
    @ObservationIgnored private var conversation: Conversation?
    @ObservationIgnored private var downloadTask: Task<Void, Never>?
    @ObservationIgnored private var stopRequested = false
    @ObservationIgnored private var cellularAccepted = false

    init() {
        target = Languages.byCode(UserDefaults.standard.string(forKey: targetKey)) ?? Languages.defaultLanguage()
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
                status = .error(error.localizedDescription, loadFailed: true)
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

    /// Frees the engine's memory under pressure; the next translation reloads it.
    func releaseIfIdle() {
        guard !translating, engineLoad == nil else { return }
        conversation = nil
        engine = nil
    }

    // MARK: Translation

    var canTranslate: Bool {
        let text = input.trimmingCharacters(in: .whitespacesAndNewlines)
        return status == .ready && !translating && !text.isEmpty && text.count <= maxInputChars
    }

    func translate() {
        guard canTranslate else { return }
        let text = input.trimmingCharacters(in: .whitespacesAndNewlines)
        let target = target
        translating = true
        output = ""
        error = nil
        Task {
            defer {
                output = cleanTranslation(output, target: target)
                translating = false
                stopRequested = false
            }
            do {
                let engine = try await loadedEngine()
                // Single-shot: a fresh conversation per translation keeps history out of the prompt.
                conversation = nil
                let conversation = try await engine.createConversation(with: ConversationConfig(
                    systemMessage: Message(buildSystemInstruction(target), role: .system),
                    samplerConfig: try SamplerConfig(topK: 1, topP: 1.0, temperature: 0.0),
                    thinkingConfig: ThinkingConfig(enableThinking: false)
                ))
                self.conversation = conversation
                if stopRequested { return }
                for try await chunk in conversation.sendMessageStream(Message(text)) {
                    for case .text(let piece) in chunk.contents { output += piece }
                }
            } catch {
                if !stopRequested { self.error = error.localizedDescription }
            }
        }
    }

    func stop() {
        guard translating else { return }
        stopRequested = true
        try? conversation?.cancel()
    }

    func clear() {
        input = ""
        output = ""
    }
}
