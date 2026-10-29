import SwiftUI
import UIKit

let aboutText = "Funciona con Gemma 4 E2B de Google (licencia Apache-2.0), dentro de este móvil. El audio se procesa en el teléfono y se borra al instante."
private let installVoicesHint = "Las voces se instalan en Ajustes › Accesibilidad › Contenido leído › Voces."

struct ContentView: View {
    @Environment(TranslatorModel.self) private var model
    @Environment(\.scenePhase) private var scenePhase
    @Environment(\.openURL) private var openURL
    /// Non-nil = picker open; its languages are shown first as "Parecidos".
    @State private var picker: [Language]?

    var body: some View {
        @Bindable var model = model
        NavigationStack {
            Group {
                switch model.status {
                case .ready:
                    if model.coworker == nil {
                        ChooseLanguageView(onSearch: { picker = [] })
                    } else {
                        ConversationView(onChangeLanguage: { picker = $0 })
                    }
                case .loading:
                    ProgressView("Cargando el modelo…").frame(maxWidth: .infinity, maxHeight: .infinity)
                default:
                    DownloadView()
                }
            }
            .navigationTitle("Traductor Walter")
            .navigationBarTitleDisplayMode(.inline)
        }
        .sheet(isPresented: Binding(get: { picker != nil }, set: { if !$0 { picker = nil } })) {
            LanguagePicker(related: picker ?? []) { language in
                model.setCoworker(language)
                picker = nil
            }
        }
        .sheet(isPresented: Binding(get: { model.detectSheet != nil }, set: { if !$0 { model.closeDetection() } })) {
            DetectionSheet(onSearch: {
                model.closeDetection()
                picker = []
            })
        }
        .alert("Falta permiso de micrófono", isPresented: $model.micDenied) {
            Button("Abrir ajustes") {
                if let url = URL(string: UIApplication.openSettingsURLString) { openURL(url) }
            }
            Button("Cerrar", role: .cancel) {}
        } message: {
            Text("Para traducir lo que se dice, la app necesita el micrófono. El audio nunca sale del teléfono.")
        }
        .overlay(alignment: .top) { MessageToast() }
        .onReceive(NotificationCenter.default.publisher(for: UIApplication.didReceiveMemoryWarningNotification)) { _ in
            model.releaseIfIdle()
        }
        .onChange(of: scenePhase, initial: true) { _, phase in
            switch phase {
            // Foreground-only download: pause (keeping resume data) so it survives the app being killed.
            case .background: model.cancelDownload()
            // Voices may have been installed in Settings, and the volume may have changed.
            case .active: model.refreshDevice()
            default: break
            }
        }
    }
}

// MARK: - Download

private struct DownloadView: View {
    @Environment(TranslatorModel.self) private var model

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                Text("Descarga el modelo de idiomas").font(.title2.bold())
                    .accessibilityAddTraits(.isHeader)
                Text("Para traducir sin internet, la app necesita descargar un modelo de idiomas una sola vez (\(formatGB(Model.sizeBytes))). Después funciona sin conexión.")
                Text("Mejor con wifi. Espacio libre necesario: unos \(formatGB(Model.sizeBytes + 200_000_000)). Se descarga de Hugging Face.")
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
        .alert("¿Usar datos móviles?", isPresented: .constant(model.status == .needsCellularConsent)) {
            Button("Descargar igualmente") { model.acceptCellular() }
            Button("Esperar al wifi", role: .cancel) { model.waitForWifi() }
        } message: {
            Text("Son \(formatGB(Model.sizeBytes)) de descarga. ¿Usar datos móviles?")
        }
    }

    @ViewBuilder private var actions: some View {
        switch model.status {
        case .downloading(let bytes):
            ProgressView(value: Double(bytes), total: Double(Model.sizeBytes)) {
                Text("\(formatGB(bytes)) de \(formatGB(Model.sizeBytes))")
            }
            Text("Deja la app abierta hasta que termine la descarga.").font(.footnote)
            Button("Cancelar") { model.cancelDownload() }
                .buttonStyle(.glass)
                .frame(maxWidth: .infinity)
        case .verifying:
            ProgressView("Comprobando la descarga…").frame(maxWidth: .infinity)
        case .error(let message, let loadFailed):
            Text(message).foregroundStyle(.red)
            if loadFailed {
                primaryButton("Borrar y descargar de nuevo") { model.deleteModelAndDownloadAgain() }
            } else {
                primaryButton("Reintentar") { model.download() }
            }
        case .missing(let partial):
            primaryButton(partial == 0 ? "Descargar" : "Continuar (\(formatGB(partial)) de \(formatGB(Model.sizeBytes)))") {
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

// MARK: - Choose language (first launch)

private struct ChooseLanguageView: View {
    @Environment(TranslatorModel.self) private var model
    let onSearch: () -> Void

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 20) {
                Text("¿Qué idioma habla tu compañero?")
                    .font(.largeTitle.bold())
                    .accessibilityAddTraits(.isHeader)
                GlassEffectContainer {
                    VStack(spacing: 16) {
                        Button { model.openDetection() } label: {
                            Text("🎤 Que hable él").font(.title.bold()).frame(maxWidth: .infinity, minHeight: 96)
                        }
                        .buttonStyle(.glassProminent)
                        .tint(.orange)
                        Button(action: onSearch) {
                            Text("🔍 Buscar en la lista").font(.title2).frame(maxWidth: .infinity, minHeight: 72)
                        }
                        .buttonStyle(.glass)
                    }
                    .buttonBorderShape(.roundedRectangle(radius: 28))
                }
                if model.lowRam {
                    Text(lowRamWarning).font(.footnote).foregroundStyle(.red)
                }
                AboutText()
            }
            .padding(24)
        }
    }
}

// MARK: - Conversation

private struct ConversationView: View {
    @Environment(TranslatorModel.self) private var model
    let onChangeLanguage: ([Language]) -> Void
    @State private var writing = false
    @State private var confirmNew = false
    @State private var shown: ShownText?

    var body: some View {
        let coworker = model.coworker ?? Languages.spanish
        ScrollViewReader { proxy in
            ScrollView {
                LazyVStack(spacing: 12) {
                    if let detected = model.confirmLanguage {
                        ConfirmBanner(language: detected, onChange: { onChangeLanguage(Languages.related(detected)) })
                    }
                    if model.voiceless.contains(Languages.spanish.code) {
                        Notice(text: "Falta la voz española en el teléfono. \(installVoicesHint)")
                    }
                    if model.lowVolumeNotice {
                        Notice(text: "Sube el volumen 🔊", action: "Vale") { model.dismissLowVolume() }
                    }
                    if model.turns.isEmpty {
                        Text("Toca un botón y habla. La traducción se dice en voz alta.")
                            .font(.title3)
                            .foregroundStyle(.secondary)
                            .multilineTextAlignment(.center)
                            .frame(maxWidth: .infinity)
                            .padding(.top, 32)
                    }
                    ForEach(model.turns) { turn in
                        Bubble(turn: turn, newest: turn.id == model.turns.last?.id) { shown = ShownText(text: turn.translation) }
                            .id(turn.id)
                    }
                }
                .padding()
            }
            .onChange(of: model.turns.last?.translation) {
                if let last = model.turns.last { withAnimation { proxy.scrollTo(last.id, anchor: .bottom) } }
            }
        }
        .safeAreaInset(edge: .top) { header(coworker) }
        .safeAreaInset(edge: .bottom) { controls(coworker) }
        .sheet(isPresented: $writing) {
            WritingSheet(coworker: coworker) { text in
                model.translateText(text)
                writing = false
            }
        }
        .alert("¿Empezar una conversación nueva?", isPresented: $confirmNew) {
            Button("Nueva", role: .destructive) { model.newConversation() }
            Button("Cancelar", role: .cancel) {}
        } message: {
            Text("Se borra lo que hay en la pantalla.")
        }
        .fullScreenCover(item: $shown) { ShowFullScreen(text: $0.text) }
        .modifier(Haptics(listening: { if case .listening = model.activity { true } else { false } }()))
        .onChange(of: model.turns.last?.done) { _, done in
            // Announce once when finished, not per streamed token.
            if done == true, let last = model.turns.last, last.error == nil {
                AccessibilityNotification.Announcement(last.translation).post()
            }
        }
        .onAppear { UIApplication.shared.isIdleTimerDisabled = true }
        .onDisappear { UIApplication.shared.isIdleTimerDisabled = false }
    }

    private func header(_ coworker: Language) -> some View {
        GlassEffectContainer {
            HStack(spacing: 12) {
                Button { onChangeLanguage([]) } label: {
                    HStack {
                        Text("Compañero: \(coworker.nativeName) · \(coworker.spanishName)"
                             + (model.voiceless.contains(coworker.code) ? " 🔇" : ""))
                            .lineLimit(1)
                        Image(systemName: "chevron.down")
                    }
                    .font(.headline)
                    .frame(maxWidth: .infinity, minHeight: 44, alignment: .leading)
                }
                .buttonStyle(.glass)
                .accessibilityLabel("Idioma del compañero: \(coworker.spanishName). Cambiar")
                Button { model.openDetection() } label: {
                    Text("🎤?").font(.headline).frame(minWidth: 44, minHeight: 44)
                }
                .buttonStyle(.glass)
                .accessibilityLabel("Detectar idioma")
            }
            .padding(.horizontal)
            .padding(.top, 4)
        }
    }

    private func controls(_ coworker: Language) -> some View {
        let idle = model.activity == .idle || { if case .speaking = model.activity { true } else { false } }()
        return GlassEffectContainer {
            VStack(spacing: 8) {
                HStack(spacing: 12) {
                    MicButton(side: .walter, label: "HABLO YO", word: nil)
                    MicButton(side: .coworker, label: "HABLA ÉL", word: coworker.speakWord)
                }
                HStack {
                    Button { writing = true } label: {
                        Label("Escribir", systemImage: "keyboard").font(.title3).frame(minHeight: 44)
                    }
                    .disabled(!idle)
                    Spacer()
                    Button { confirmNew = true } label: {
                        Label("Nueva", systemImage: "trash").font(.title3).frame(minHeight: 44)
                    }
                    .disabled(!idle || model.turns.isEmpty)
                }
                .buttonStyle(.glass)
            }
            .padding(.horizontal)
            .padding(.bottom, 8)
        }
    }
}

private struct ShownText: Identifiable {
    let id = UUID()
    let text: String
}

/// One of the two big buttons: idle → Escuchando… → Traduciendo… → Hablando… (§5.2).
private struct MicButton: View {
    @Environment(TranslatorModel.self) private var model
    let side: Side
    let label: String
    let word: String?

    var body: some View {
        let activity = model.activity
        var listening = false, translating = false, speaking = false, enabled = true
        var level: Float = 0, elapsedMs = 0
        switch activity {
        case .idle:
            break
        case .listening(let s, let l, let ms, let detecting):
            listening = s == side && !detecting
            enabled = listening
            level = l
            elapsedMs = ms
        case .translating(let s):
            translating = s == side
            enabled = translating
        case .detecting:
            enabled = false
        case .speaking(let turnId):
            speaking = model.turns.first { $0.id == turnId }?.side == side
        }
        let (title, subtitle): (String, String?) =
            if listening { ("Escuchando…", elapsedMs >= warnRecordingMs ? "Termina la frase…" : "toca para terminar") }
            else if translating { ("Traduciendo…", "toca para parar") }
            else if speaking { ("Hablando…", "toca para parar") }
            else { (label, word) }
        let pulse = model.hint == side && activity == .idle
        return Button { model.onMic(side) } label: {
            VStack(spacing: 6) {
                if translating {
                    ProgressView().controlSize(.large).tint(.white)
                } else {
                    Image(systemName: listening || speaking ? "stop.fill" : "mic.fill").font(.system(size: 36))
                }
                Text(title).font(.title2.bold()).multilineTextAlignment(.center)
                if listening {
                    ProgressView(value: Double(level)).tint(.white).frame(maxWidth: 120)
                }
                if let subtitle {
                    Text(subtitle).font(.headline).multilineTextAlignment(.center).lineLimit(2)
                }
            }
            .foregroundStyle(.white)
            .frame(maxWidth: .infinity, minHeight: 140)
        }
        .buttonStyle(.glassProminent)
        .buttonBorderShape(.roundedRectangle(radius: 28))
        .tint(side == .walter ? .blue : .orange)
        .disabled(!enabled)
        .phaseAnimator(pulse ? [1.0, 1.05] : [1.0]) { content, scale in
            content.scaleEffect(scale)
        } animation: { _ in .easeInOut(duration: 0.7) }
        .accessibilityLabel(title)
        .accessibilityHint(subtitle ?? "")
    }
}

/// Light tick when listening starts, double tick when it stops (§4.2).
private struct Haptics: ViewModifier {
    let listening: Bool
    @State private var start = 0
    @State private var stop = 0

    func body(content: Content) -> some View {
        content
            .sensoryFeedback(.impact(weight: .light), trigger: start)
            .sensoryFeedback(.impact(weight: .medium), trigger: stop)
            .onChange(of: listening) { _, listening in
                if listening {
                    start += 1
                } else {
                    stop += 1
                    Task {
                        try? await Task.sleep(for: .milliseconds(120))
                        stop += 1
                    }
                }
            }
    }
}

private struct Bubble: View {
    @Environment(TranslatorModel.self) private var model
    let turn: Turn
    let newest: Bool
    let onShow: () -> Void

    var body: some View {
        let walter = turn.side == .walter
        let voiceless = model.voiceless.contains(turn.target.code)
        let speaking = model.activity == .speaking(turnId: turn.id)
        let canAct = model.activity == .idle || { if case .speaking = model.activity { true } else { false } }()
        HStack {
            if walter { Spacer(minLength: 40) }
            VStack(alignment: .leading, spacing: 6) {
                Text((walter ? "Walter" : "Compañero") + (turn.typed ? " ⌨" : ""))
                    .font(.subheadline.weight(.semibold))
                    .accessibilityHidden(true)
                if !turn.translation.isEmpty {
                    Text(turn.translation)
                        .font(newest || voiceless ? .title.weight(.semibold) : .title3)
                        .textSelection(.enabled)
                        .accessibilityLabel(turn.done ? turn.translation : "Traduciendo")
                }
                if !turn.transcript.isEmpty {
                    Text(turn.transcript).font(.body).foregroundStyle(.secondary)
                }
                if !turn.done && turn.translation.isEmpty {
                    ProgressView().accessibilityLabel("Traduciendo")
                }
                if let error = turn.error {
                    Text(error).font(.headline).foregroundStyle(.red)
                    Button("Reintentar") { model.retry(turn.id) }
                        .buttonStyle(.bordered)
                        .controlSize(.large)
                        .disabled(!canAct)
                }
                if turn.done && turn.error == nil && !turn.translation.isEmpty {
                    if voiceless {
                        Text("Enséñale la pantalla 👀").font(.headline)
                    }
                    HStack {
                        Spacer()
                        Button("Mostrar", action: onShow)
                            .font(.headline)
                            .frame(minHeight: 44)
                        if !voiceless {
                            Button {
                                if speaking { model.stopSpeaking() } else { model.repeatTurn(turn.id) }
                            } label: {
                                Label(speaking ? "Parar la voz" : "Repetir en voz alta",
                                      systemImage: speaking ? "stop.fill" : "speaker.wave.2.fill")
                                    .labelStyle(.iconOnly)
                                    .font(.title2)
                                    .frame(minWidth: 56, minHeight: 56)
                                    .contentShape(.rect)
                            }
                            .disabled(!canAct)
                        }
                    }
                    .buttonStyle(.borderless)
                }
            }
            .padding(16)
            .frame(maxWidth: 340, alignment: .leading)
            .background(walter ? Color.blue.opacity(0.16) : Color.orange.opacity(0.18), in: .rect(cornerRadius: 24))
            if !walter { Spacer(minLength: 40) }
        }
    }
}

private struct ConfirmBanner: View {
    @Environment(TranslatorModel.self) private var model
    let language: Language
    let onChange: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("\(language.nativeName) · \(language.spanishName)").font(.title2.bold())
            Text("¿Correcto?").font(.headline)
            HStack(spacing: 12) {
                Button("Sí") { model.confirmDetected() }
                    .buttonStyle(.borderedProminent)
                Button("Cambiar") {
                    model.confirmDetected()
                    onChange()
                }
                .buttonStyle(.bordered)
            }
            .controlSize(.large)
        }
        .padding(16)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Color.green.opacity(0.16), in: .rect(cornerRadius: 24))
    }
}

private struct Notice: View {
    let text: String
    var action: String?
    var onAction: () -> Void = {}

    var body: some View {
        HStack {
            Text(text).font(.headline)
            Spacer()
            if let action {
                Button(action, action: onAction).frame(minHeight: 44)
            }
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 8)
        .background(.fill.tertiary, in: .rect(cornerRadius: 16))
    }
}

/// Short notices ("No he oído nada", …) shown for a few seconds above the content.
private struct MessageToast: View {
    @Environment(TranslatorModel.self) private var model

    var body: some View {
        if let message = model.message {
            Text(message)
                .font(.headline)
                .multilineTextAlignment(.center)
                .padding(.horizontal, 20)
                .padding(.vertical, 12)
                .glassEffect(.regular, in: .capsule)
                .padding(.top, 60)
                .padding(.horizontal)
                .onTapGesture { model.message = nil }
                .task(id: message) {
                    AccessibilityNotification.Announcement(message).post()
                    try? await Task.sleep(for: .seconds(4))
                    if model.message == message { model.message = nil }
                }
        }
    }
}

/// Full screen, white on black, the largest font that fits: for the coworker to read (§4.2).
private struct ShowFullScreen: View {
    @Environment(\.dismiss) private var dismiss
    let text: String

    var body: some View {
        Text(text)
            .font(.system(size: 160, weight: .semibold))
            .minimumScaleFactor(0.05)
            .multilineTextAlignment(.center)
            .foregroundStyle(.white)
            .padding(24)
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .background(.black)
            .contentShape(.rect)
            .onTapGesture { dismiss() }
            .accessibilityAction(named: "Cerrar") { dismiss() }
            .statusBarHidden()
    }
}

private struct WritingSheet: View {
    @Environment(\.dismiss) private var dismiss
    let coworker: Language
    let onTranslate: (String) -> Void
    @State private var text = ""

    var body: some View {
        let tooLong = text.count > maxInputChars
        NavigationStack {
            VStack(alignment: .trailing, spacing: 12) {
                TextEditor(text: $text)
                    .frame(minHeight: 140)
                    .scrollContentBackground(.hidden)
                    .padding(12)
                    .background(.fill.tertiary, in: .rect(cornerRadius: 20))
                    .accessibilityLabel("En español o en \(coworker.spanishName.lowercased())")
                Text("\(text.count) / \(maxInputChars)")
                    .font(.caption)
                    .foregroundStyle(tooLong ? .red : .secondary)
                Button { onTranslate(text) } label: {
                    Text("Traducir").font(.title3.bold()).frame(maxWidth: .infinity, minHeight: 52)
                }
                .buttonStyle(.glassProminent)
                .disabled(text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || tooLong)
                Spacer()
            }
            .padding()
            .navigationTitle("En español o en \(coworker.spanishName.lowercased())")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cerrar") { dismiss() } }
            }
        }
        .presentationDetents([.medium, .large])
    }
}

private struct DetectionSheet: View {
    @Environment(TranslatorModel.self) private var model
    let onSearch: () -> Void

    var body: some View {
        var listening = false
        var level: Float = 0
        if case .listening(_, let l, _, true) = model.activity {
            listening = true
            level = l
        }
        let canListen = model.activity == .idle || listening
        return VStack(spacing: 20) {
            if model.activity == .detecting {
                ProgressView("Detectando idioma…").controlSize(.large)
            } else if model.detectSheet == .failed {
                Text("No lo he reconocido").font(.title2.bold())
                Button { model.openDetection() } label: {
                    Text("Probar otra vez").font(.title3.bold()).frame(maxWidth: .infinity, minHeight: 56)
                }
                .buttonStyle(.glassProminent)
                Button(action: onSearch) {
                    Text("🔍 Buscar en la lista").font(.title3).frame(maxWidth: .infinity, minHeight: 56)
                }
                .buttonStyle(.glass)
            } else {
                Text("Pídele que diga una frase en su idioma")
                    .font(.title2.bold())
                    .multilineTextAlignment(.center)
                Button { model.onMic(.coworker, detecting: true) } label: {
                    Image(systemName: listening ? "stop.fill" : "mic.fill")
                        .font(.system(size: 64))
                        .foregroundStyle(.white)
                        .frame(width: 160, height: 160)
                }
                .buttonStyle(.glassProminent)
                .buttonBorderShape(.circle)
                .tint(.orange)
                .disabled(!canListen)
                .accessibilityLabel(listening ? "Terminar" : "Escuchar")
                if listening {
                    Text("Escuchando… toca para terminar").font(.headline)
                    ProgressView(value: Double(level)).frame(maxWidth: 200)
                }
                Button("Buscar en la lista", action: onSearch).frame(minHeight: 44)
            }
        }
        .padding(24)
        .frame(maxWidth: .infinity)
        .presentationDetents([.medium, .large])
    }
}

private struct LanguagePicker: View {
    @Environment(TranslatorModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    let related: [Language]
    let onSelect: (Language) -> Void
    @State private var query = ""

    var body: some View {
        let results = Languages.search(query)
        let browsing = query.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
        NavigationStack {
            List {
                if browsing && !related.isEmpty {
                    Section("Parecidos") { ForEach(related) { row($0) } }
                }
                if browsing && !model.recents.isEmpty {
                    Section("Recientes") { ForEach(model.recents) { row($0) } }
                }
                Section(browsing ? "Todos" : "") { ForEach(results) { row($0) } }
                Section {
                    Text("🔇 = sin voz en este teléfono. \(installVoicesHint)")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }
            }
            .overlay {
                if results.isEmpty { ContentUnavailableView("No hay ningún idioma con ese nombre", systemImage: "magnifyingglass") }
            }
            .searchable(text: $query, prompt: "Buscar idioma")
            .navigationTitle("Idioma del compañero")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cerrar") { dismiss() } }
            }
        }
    }

    private func row(_ language: Language) -> some View {
        let selected = language == model.coworker
        let voiceless = model.voiceless.contains(language.code)
        return Button { onSelect(language) } label: {
            HStack {
                VStack(alignment: .leading) {
                    Text(language.nativeName).font(.title3)
                    Text(language.spanishName + (voiceless ? "  🔇" : "")).font(.subheadline).foregroundStyle(.secondary)
                }
                Spacer()
                if selected { Image(systemName: "checkmark").foregroundStyle(.tint) }
            }
            .frame(minHeight: 44)
        }
        .tint(.primary)
        .accessibilityLabel(language.spanishName + (voiceless ? ", sin voz" : ""))
        .accessibilityAddTraits(selected ? .isSelected : [])
    }
}

/// Model credit (§11). Plain text: the licence link lives in the README, so the app holds no URL but the model's.
private struct AboutText: View {
    var body: some View {
        Text(aboutText)
            .font(.footnote)
            .foregroundStyle(.secondary)
            .frame(maxWidth: .infinity, alignment: .leading)
    }
}

#Preview {
    ContentView()
        .environment(TranslatorModel())
}
