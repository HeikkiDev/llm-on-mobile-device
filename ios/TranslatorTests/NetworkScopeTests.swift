import Foundation
import Testing
@testable import Translator

/// SPEC.md §8: networking is confined to ModelDownloader.swift and the only URL is the model URL.
struct NetworkScopeTests {
    private static let appSources: [(name: String, text: String)] = {
        let dir = URL(filePath: #filePath).deletingLastPathComponent().deletingLastPathComponent()
            .appending(path: "TranslatorApp")
        let files = (try? FileManager.default.contentsOfDirectory(at: dir, includingPropertiesForKeys: nil)) ?? []
        return files.filter { $0.pathExtension == "swift" }
            .map { ($0.lastPathComponent, (try? String(contentsOf: $0, encoding: .utf8)) ?? "") }
    }()

    private func files(containing pattern: String) -> Set<String> {
        Set(Self.appSources.filter { $0.text.range(of: pattern, options: .regularExpression) != nil }.map(\.name))
    }

    @Test func sourcesAreReadable() {
        #expect(Self.appSources.count >= 5)
    }

    @Test func urlSessionOnlyInModelDownloader() {
        #expect(files(containing: "URLSession") == ["ModelDownloader.swift"])
    }

    @Test func noOtherNetworkingOrAnalytics() {
        #expect(files(containing: #"import Network\b|NWConnection|NWPathMonitor|CFNetwork|Firebase|Analytics"#).isEmpty)
    }

    @Test func onlyUrlIsTheModelUrl() {
        let urls = Self.appSources.flatMap { $0.text.matches(of: /https?:\/\/[^"\s)]+/).map { String($0.output) } }
        #expect(urls == [Model.url.absoluteString])
    }
}

struct DownloaderTests {
    @Test func serverErrorsAskToTryLater() {
        #expect(httpErrorMessage(503).hasPrefix("Model server unavailable, try later"))
        #expect(httpErrorMessage(404).contains("HTTP 404"))
        #expect(httpErrorMessage(400) == "Download failed (HTTP 400).")
    }
}
