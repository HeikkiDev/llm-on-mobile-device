import SwiftUI

struct ContentView: View {
    @Environment(TranslatorModel.self) private var model
    @Environment(\.scenePhase) private var scenePhase

    var body: some View {
        NavigationStack {
            Group {
                switch model.status {
                case .ready:
                    TranslatorView()
                case .loading:
                    ProgressView("Loading model…").frame(maxWidth: .infinity, maxHeight: .infinity)
                default:
                    DownloadView()
                }
            }
            .navigationTitle("Translate")
        }
        .onReceive(NotificationCenter.default.publisher(for: UIApplication.didReceiveMemoryWarningNotification)) { _ in
            model.releaseIfIdle()
        }
        .onChange(of: scenePhase) { _, phase in
            // Foreground-only download: pause (keeping resume data) so it survives the app being killed.
            if phase == .background { model.cancelDownload() }
        }
    }
}

private struct DownloadView: View {
    @Environment(TranslatorModel.self) private var model

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                Text("Download the language model").font(.title2.bold())
                    .accessibilityAddTraits(.isHeader)
                Text("To translate offline, this app needs to download a language model once (\(formatGB(Model.sizeBytes))). After that it works without internet.")
                Text("Wi-Fi recommended. Free space needed: about \(formatGB(Model.sizeBytes + 200_000_000)). Downloaded from Hugging Face.")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                if model.lowRam {
                    Text(lowRamWarning).foregroundStyle(.red)
                }
                actions
                AboutText()
            }
            .padding(24)
            .glassEffect(.regular, in: .rect(cornerRadius: 24))
            .padding()
        }
        .alert("Use mobile data?", isPresented: .constant(model.status == .needsCellularConsent)) {
            Button("Download anyway") { model.acceptCellular() }
            Button("Wait for Wi-Fi", role: .cancel) { model.waitForWifi() }
        } message: {
            Text("This is a \(formatGB(Model.sizeBytes)) download. Use mobile data?")
        }
    }

    @ViewBuilder private var actions: some View {
        switch model.status {
        case .downloading(let bytes):
            ProgressView(value: Double(bytes), total: Double(Model.sizeBytes)) {
                Text("\(formatGB(bytes)) of \(formatGB(Model.sizeBytes))")
            }
            Text("Keep the app open until the download finishes.").font(.footnote)
            Button("Cancel") { model.cancelDownload() }
                .buttonStyle(.glass)
                .frame(maxWidth: .infinity)
        case .verifying:
            ProgressView("Verifying download…").frame(maxWidth: .infinity)
        case .error(let message, let loadFailed):
            Text(message).foregroundStyle(.red)
            if loadFailed {
                primaryButton("Delete and download again") { model.deleteModelAndDownloadAgain() }
            } else {
                primaryButton("Retry") { model.download() }
            }
        case .missing(let partial):
            primaryButton(partial == 0 ? "Download" : "Resume (\(formatGB(partial)) of \(formatGB(Model.sizeBytes)))") {
                model.download()
            }
        default:
            EmptyView()
        }
    }

    private func primaryButton(_ title: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Text(title).frame(maxWidth: .infinity)
        }
        .buttonStyle(.glassProminent)
        .controlSize(.large)
    }
}

private struct TranslatorView: View {
    @Environment(TranslatorModel.self) private var model
    @State private var pickingLanguage = false
    @ScaledMetric private var editorHeight = 140

    var body: some View {
        @Bindable var model = model
        let tooLong = model.input.count > maxInputChars
        ScrollView {
            VStack(spacing: 16) {
                if model.lowRam {
                    Text(lowRamWarning)
                        .font(.footnote)
                        .foregroundStyle(.red)
                        .frame(maxWidth: .infinity, alignment: .leading)
                }
                VStack(alignment: .trailing, spacing: 4) {
                    TextEditor(text: $model.input)
                        .frame(minHeight: min(editorHeight, 220))
                        .scrollContentBackground(.hidden)
                        .accessibilityLabel("Text to translate")
                        .padding(12)
                        .padding(.trailing, model.input.isEmpty ? 0 : 34)
                        .background(.fill.tertiary, in: .rect(cornerRadius: 20))
                        .overlay(alignment: .topTrailing) {
                            if !model.input.isEmpty {
                                IconButton("Clear text", systemImage: "xmark.circle.fill") { model.input = "" }
                                    .foregroundStyle(.secondary)
                                    .padding(2)
                            }
                        }
                    Text(tooLong ? "Too long: \(model.input.count) / \(maxInputChars) characters" : "\(model.input.count) / \(maxInputChars)")
                        .font(.caption)
                        .foregroundStyle(tooLong ? .red : .secondary)
                        .accessibilityLabel("\(model.input.count) of \(maxInputChars) characters")
                }
                if model.translating || !model.output.isEmpty {
                    OutputCard()
                }
                AboutText()
            }
            .padding()
        }
        .scrollDismissesKeyboard(.interactively)
        .safeAreaInset(edge: .bottom) { controls }
        .sheet(isPresented: $pickingLanguage) {
            LanguagePicker(selection: $model.target)
        }
        .alert("Translation failed", isPresented: .constant(model.error != nil)) {
            Button("OK") { model.error = nil }
        } message: {
            Text(model.error ?? "")
        }
        .onChange(of: model.translating) { wasTranslating, translating in
            // Announce once when finished, not per streamed token.
            if wasTranslating, !translating, !model.output.isEmpty {
                AccessibilityNotification.Announcement(model.output).post()
            }
        }
    }

    private var controls: some View {
        GlassEffectContainer {
            // Side by side when it fits; stacked at the largest accessibility text sizes.
            ViewThatFits(in: .horizontal) {
                HStack { languageButton; Spacer(); translateButton }
                VStack(alignment: .leading) { languageButton; translateButton }
            }
            .controlSize(.large)
            .padding()
        }
    }

    private var languageButton: some View {
        Button { pickingLanguage = true } label: {
            Text("To: \(model.target.englishName)").lineLimit(1)
        }
        .buttonStyle(.glass)
        .accessibilityLabel("Translate to \(model.target.englishName)")
        .accessibilityHint("Change language")
    }

    @ViewBuilder private var translateButton: some View {
        if model.translating {
            Button("Stop") { model.stop() }.buttonStyle(.glassProminent)
        } else {
            Button("Translate") { model.translate() }
                .buttonStyle(.glassProminent)
                .disabled(!model.canTranslate)
        }
    }
}

private struct OutputCard: View {
    @Environment(TranslatorModel.self) private var model
    @State private var copied = false

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text(model.target.englishName)
                .font(.subheadline.weight(.semibold))
                .foregroundStyle(.tint)
                .accessibilityHidden(true)
            if model.translating && model.output.isEmpty {
                ProgressView().accessibilityLabel("Translating")
            }
            Text(model.output)
                .font(.title3)
                .textSelection(.enabled)
                .frame(maxWidth: .infinity, alignment: .leading)
                .accessibilityLabel(model.translating ? "Translating" : "\(model.target.englishName) translation: \(model.output)")
            if !model.translating && !model.output.isEmpty {
                HStack {
                    Spacer()
                    IconButton("Copy translation", systemImage: copied ? "checkmark" : "doc.on.doc") { copy() }
                    IconButton("Clear", systemImage: "xmark") { model.clear() }
                }
                .font(.title3)
            }
        }
        .padding(20)
        .glassEffect(.regular, in: .rect(cornerRadius: 24))
        .sensoryFeedback(.success, trigger: copied) { _, new in new }
    }

    private func copy() {
        UIPasteboard.general.string = model.output
        AccessibilityNotification.Announcement("Copied").post()
        copied = true
        Task {
            try? await Task.sleep(for: .seconds(1.5))
            copied = false
        }
    }
}

/// Model credit (§11). Plain text: the licence link lives in the README, so the app holds no URL but the model's.
private struct AboutText: View {
    var body: some View {
        Text("Powered by Gemma 4 E2B from Google (Apache-2.0 licence), running on this device.")
            .font(.footnote)
            .foregroundStyle(.secondary)
            .frame(maxWidth: .infinity, alignment: .leading)
    }
}

/// Icon-only button with a 44 pt touch target.
private struct IconButton: View {
    let title: String
    let systemImage: String
    let action: () -> Void

    init(_ title: String, systemImage: String, action: @escaping () -> Void) {
        self.title = title
        self.systemImage = systemImage
        self.action = action
    }

    var body: some View {
        Button(action: action) {
            Label(title, systemImage: systemImage)
                .labelStyle(.iconOnly)
                .frame(minWidth: 44, minHeight: 44)
                .contentShape(.rect)
        }
        .buttonStyle(.borderless)
    }
}

private struct LanguagePicker: View {
    @Binding var selection: Language
    @Environment(\.dismiss) private var dismiss
    @State private var query = ""

    var body: some View {
        let results = Languages.search(query)
        NavigationStack {
            List(results) { language in
                Button {
                    selection = language
                    dismiss()
                } label: {
                    HStack {
                        VStack(alignment: .leading) {
                            Text(language.englishName)
                            Text(language.nativeName).font(.footnote).foregroundStyle(.secondary)
                        }
                        Spacer()
                        if language == selection { Image(systemName: "checkmark") }
                    }
                }
                .tint(.primary)
                .accessibilityAddTraits(language == selection ? .isSelected : [])
            }
            .overlay {
                if results.isEmpty { ContentUnavailableView("No languages found", systemImage: "magnifyingglass") }
            }
            .searchable(text: $query, prompt: "Search languages")
            .navigationTitle("Translate to")
            .navigationBarTitleDisplayMode(.inline)
        }
    }
}

#Preview {
    ContentView()
        .environment(TranslatorModel())
}
