import CryptoKit
import Foundation
import Synchronization

// The only model this app uses (SPEC.md §2.1). Change these together, never one alone.
enum Model {
    static let file = "gemma-4-E2B-it.litertlm"
    static let url = URL(string: "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/6e5c4f1e395deb959c494953478fa5cec4b8008f/gemma-4-E2B-it.litertlm")!
    static let sizeBytes: Int64 = 2_588_147_712
    static let sha256 = "181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c"
    static let verifySHA256 = true
}

private let freeSpaceMarginBytes: Int64 = 200_000_000
private let bufferBytes = 1 << 20
private let progressInterval: TimeInterval = 0.5

enum DownloadError: LocalizedError, Equatable {
    case noConnection
    case expensiveNetwork
    case notEnoughSpace(Int64)
    case http(Int)
    case corrupted
    case saveFailed

    var errorDescription: String? {
        switch self {
        case .noConnection: "Sin conexión. Revisa internet y vuelve a intentarlo."
        case .expensiveNetwork: "Esperando al wifi."
        case .notEnoughSpace(let needed): "No hay espacio suficiente. Libera unos \(formatGB(needed)) y vuelve a intentarlo."
        case .http(let code): httpErrorMessage(code)
        case .corrupted: "La descarga está dañada. Vuelve a intentarlo."
        case .saveFailed: "No se ha podido guardar el modelo. Vuelve a intentarlo."
        }
    }
}

func httpErrorMessage(_ code: Int) -> String {
    switch code {
    case 403, 404, 410, 429, 500...599: "El servidor del modelo no está disponible. Prueba más tarde (HTTP \(code))."
    default: "La descarga ha fallado (HTTP \(code))."
    }
}

func formatGB(_ bytes: Int64) -> String {
    String(format: "%.1f GB", locale: Locale(identifier: "en_US_POSIX"), Double(bytes) / 1e9)
}

/// URLSession resume data plus the byte count it covers (the data itself is opaque).
private struct SavedResume: Codable {
    let bytes: Int64
    let data: Data
}

/// One-time foreground download of the model into Application Support. Holds no UI state.
final class ModelDownloader: NSObject, URLSessionDownloadDelegate, Sendable {
    let modelURL: URL
    private let partURL: URL
    private let resumeURL: URL

    private struct Run {
        var task: URLSessionDownloadTask?
        var continuation: CheckedContinuation<Void, Error>?
        var finishError: Error?
        var onProgress: (@Sendable (Int64) -> Void)?
        var bytes: Int64 = 0
        var lastReport = Date.distantPast
    }
    private let run = Mutex(Run())

    override init() {
        let fm = FileManager.default
        let support = fm.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        try? fm.createDirectory(at: support, withIntermediateDirectories: true)
        modelURL = support.appending(path: Model.file)
        partURL = support.appending(path: Model.file + ".part")
        resumeURL = fm.urls(for: .cachesDirectory, in: .userDomainMask)[0].appending(path: "model.resume")
    }

    func isModelValid() -> Bool {
        fileSize(modelURL) == Model.sizeBytes
    }

    /// Bytes kept from an interrupted download (0 if none).
    func partialBytes() -> Int64 {
        loadResume()?.bytes ?? 0
    }

    func deleteModel() {
        try? FileManager.default.removeItem(at: modelURL)
    }

    /// Downloads, verifies and installs the model. Throws `CancellationError` after `cancel()`.
    func download(
        allowExpensive: Bool,
        onProgress: @escaping @Sendable (Int64) -> Void,
        onVerifying: @escaping @Sendable () -> Void
    ) async throws {
        let needed = Model.sizeBytes + freeSpaceMarginBytes
        let free = (try? modelURL.deletingLastPathComponent()
            .resourceValues(forKeys: [.volumeAvailableCapacityForImportantUsageKey])
            .volumeAvailableCapacityForImportantUsage) ?? 0
        if free < needed { throw DownloadError.notEnoughSpace(needed) }

        let config = URLSessionConfiguration.default
        config.allowsExpensiveNetworkAccess = allowExpensive
        config.timeoutIntervalForRequest = 30
        let session = URLSession(configuration: config, delegate: self, delegateQueue: nil)
        defer { session.finishTasksAndInvalidate() }

        if let resume = loadResume() {
            try? FileManager.default.removeItem(at: resumeURL)
            do {
                try await transfer(session.downloadTask(withResumeData: resume.data), onProgress)
            } catch let error as DownloadError where error != .noConnection && error != .expensiveNetwork {
                // Resume data can go stale (e.g. the signed CDN URL expired): restart from the pinned URL.
                try await transfer(session.downloadTask(with: Model.url), onProgress)
            }
        } else {
            try await transfer(session.downloadTask(with: Model.url), onProgress)
        }

        if Model.verifySHA256 {
            onVerifying()
            let part = partURL
            let hash = try await Task.detached(priority: .userInitiated) { try sha256Hex(of: part) }.value
            if hash != Model.sha256 {
                try? FileManager.default.removeItem(at: partURL)
                throw DownloadError.corrupted
            }
        }
        do {
            try? FileManager.default.removeItem(at: modelURL)
            try FileManager.default.moveItem(at: partURL, to: modelURL)
            var url = modelURL
            var values = URLResourceValues()
            values.isExcludedFromBackup = true
            try url.setResourceValues(values)
        } catch {
            throw DownloadError.saveFailed
        }
    }

    /// Pauses the download, keeping resume data for the next attempt.
    func cancel() {
        let (task, bytes) = run.withLock { ($0.task, $0.bytes) }
        task?.cancel { data in
            if let data { self.saveResume(data, bytes: bytes) }
        }
    }

    private func saveResume(_ data: Data, bytes: Int64) {
        try? PropertyListEncoder().encode(SavedResume(bytes: bytes, data: data)).write(to: resumeURL)
    }

    private func loadResume() -> SavedResume? {
        guard let file = try? Data(contentsOf: resumeURL) else { return nil }
        return try? PropertyListDecoder().decode(SavedResume.self, from: file)
    }

    private func transfer(_ task: URLSessionDownloadTask, _ onProgress: @escaping @Sendable (Int64) -> Void) async throws {
        try await withCheckedThrowingContinuation { continuation in
            run.withLock { $0 = Run(task: task, continuation: continuation, onProgress: onProgress) }
            task.resume()
        }
    }

    // MARK: URLSessionDownloadDelegate (called on the session's serial delegate queue)

    func urlSession(_ session: URLSession, downloadTask: URLSessionDownloadTask, didWriteData bytesWritten: Int64,
                    totalBytesWritten: Int64, totalBytesExpectedToWrite: Int64) {
        let report: (@Sendable (Int64) -> Void)? = run.withLock { state in
            state.bytes = totalBytesWritten
            let now = Date()
            guard now.timeIntervalSince(state.lastReport) >= progressInterval else { return nil }
            state.lastReport = now
            return state.onProgress
        }
        report?(totalBytesWritten)
    }

    func urlSession(_ session: URLSession, downloadTask: URLSessionDownloadTask, didFinishDownloadingTo location: URL) {
        // `location` is deleted when this returns, so validate and move it now.
        var failure: Error?
        let code = (downloadTask.response as? HTTPURLResponse)?.statusCode ?? 0
        if code != 200 && code != 206 {
            failure = DownloadError.http(code)
        } else if fileSize(location) != Model.sizeBytes {
            failure = DownloadError.corrupted
        } else {
            do {
                try? FileManager.default.removeItem(at: partURL)
                try FileManager.default.moveItem(at: location, to: partURL)
            } catch {
                failure = DownloadError.saveFailed
            }
        }
        run.withLock { $0.finishError = failure }
    }

    func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
        let (continuation, finishError, bytes) = run.withLock { state in
            defer { state = Run() }
            return (state.continuation, state.finishError, state.bytes)
        }
        guard let continuation else { return }
        guard let error = error as? URLError else {
            if let finishError { continuation.resume(throwing: finishError) } else { continuation.resume() }
            return
        }
        if let data = error.downloadTaskResumeData { saveResume(data, bytes: bytes) }
        if error.code == .cancelled {
            continuation.resume(throwing: CancellationError())
        } else if error.networkUnavailableReason == .expensive {
            continuation.resume(throwing: DownloadError.expensiveNetwork)
        } else {
            continuation.resume(throwing: DownloadError.noConnection)
        }
    }
}

private func fileSize(_ url: URL) -> Int64? {
    (try? FileManager.default.attributesOfItem(atPath: url.path)[.size] as? NSNumber)?.int64Value
}

private func sha256Hex(of url: URL) throws -> String {
    let handle = try FileHandle(forReadingFrom: url)
    defer { try? handle.close() }
    var hasher = SHA256()
    while let chunk = try handle.read(upToCount: bufferBytes), !chunk.isEmpty {
        try Task.checkCancellation()
        hasher.update(data: chunk)
    }
    return hasher.finalize().map { String(format: "%02x", $0) }.joined()
}
