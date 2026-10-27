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
                Text("To translate offline, this app needs to download a language model once (\(formatGB(Model.sizeBytes))). After that it works without internet.")
                Text("Wi-Fi recommended. Free space needed: about \(formatGB(Model.sizeBytes + 200_000_000)). Downloaded from Hugging Face.")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                actions
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

    var body: some View {
        @Bindable var model = model
        ScrollView {
            VStack(spacing: 16) {
                VStack(alignment: .trailing, spacing: 4) {
                    TextEditor(text: $model.input)
                        .frame(minHeight: 140)
                        .scrollContentBackground(.hidden)
                        .padding(12)
                        .background(.fill.tertiary, in: .rect(cornerRadius: 20))
                        .accessibilityLabel("Text to translate")
                    Text("\(model.input.count) / \(maxInputChars)")
                        .font(.caption)
                        .foregroundStyle(model.input.count > maxInputChars ? .red : .secondary)
                }
                if model.translating || !model.output.isEmpty {
                    VStack(alignment: .leading, spacing: 12) {
                        Text(model.output.isEmpty ? " " : model.output)
                            .textSelection(.enabled)
                            .frame(maxWidth: .infinity, alignment: .leading)
                        if model.translating { ProgressView() }
                    }
                    .padding(20)
                    .glassEffect(.regular, in: .rect(cornerRadius: 24))
                }
            }
            .padding()
        }
        .safeAreaInset(edge: .bottom) {
            GlassEffectContainer {
                HStack {
                    Button("To: \(model.target.englishName)") { pickingLanguage = true }
                        .buttonStyle(.glass)
                    Spacer()
                    if model.translating {
                        Button("Stop") { model.stop() }.buttonStyle(.glassProminent)
                    } else {
                        Button("Translate") { model.translate() }
                            .buttonStyle(.glassProminent)
                            .disabled(!model.canTranslate)
                    }
                }
                .controlSize(.large)
                .padding()
            }
        }
        .sheet(isPresented: $pickingLanguage) {
            LanguagePicker(selection: $model.target)
        }
        .alert("Translation failed", isPresented: .constant(model.error != nil)) {
            Button("OK") { model.error = nil }
        } message: {
            Text(model.error ?? "")
        }
    }
}

private struct LanguagePicker: View {
    @Binding var selection: Language
    @Environment(\.dismiss) private var dismiss
    @State private var query = ""

    var body: some View {
        NavigationStack {
            List(Languages.search(query)) { language in
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
