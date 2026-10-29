import AVFAudio

/// Thin wrapper over AVSpeechSynthesizer (SPEC-WALTER §4.2). Its voices are installed on the phone and
/// synthesize on-device; a language without an installed voice gets nil, and the screen is shown instead.
@MainActor
final class Speaker: NSObject, AVSpeechSynthesizerDelegate {
    private let synthesizer = AVSpeechSynthesizer()
    private var onDone: (() -> Void)?
    private var current: AVSpeechUtterance?

    override init() {
        super.init()
        synthesizer.delegate = self
    }

    /// The best installed voice for `language`, or nil. Read fresh each time: voices can be added in Settings.
    func voice(for language: Language, among voices: [AVSpeechSynthesisVoice] = AVSpeechSynthesisVoice.speechVoices()) -> AVSpeechSynthesisVoice? {
        voices
            .filter { !$0.voiceTraits.contains(.isNoveltyVoice) }
            .compactMap { voice in language.voiceFit(voice.language).map { (voice, $0) } }
            .max { ($0.1, $0.0.quality.rawValue) < ($1.1, $1.0.quality.rawValue) }?
            .0
    }

    /// Codes of the given languages that have no installed voice.
    func voiceless(_ languages: [Language]) -> Set<String> {
        let voices = AVSpeechSynthesisVoice.speechVoices()
        return Set(languages.filter { voice(for: $0, among: voices) == nil }.map(\.code))
    }

    /// Speaks `text`; `onDone` runs when it finishes. False if there is no voice for the language.
    func speak(_ text: String, language: Language, rate: Float, onDone: @escaping () -> Void) -> Bool {
        guard let voice = voice(for: language) else { return false }
        stop()
        let utterance = AVSpeechUtterance(string: text)
        utterance.voice = voice
        utterance.rate = AVSpeechUtteranceDefaultSpeechRate * rate
        current = utterance
        self.onDone = onDone
        synthesizer.speak(utterance)
        return true
    }

    func stop() {
        onDone = nil
        current = nil
        if synthesizer.isSpeaking { synthesizer.stopSpeaking(at: .immediate) }
    }

    private func finished(_ utterance: AVSpeechUtterance) {
        guard utterance === current else { return }
        let callback = onDone
        onDone = nil
        current = nil
        callback?()
    }

    nonisolated func speechSynthesizer(_ synthesizer: AVSpeechSynthesizer, didFinish utterance: AVSpeechUtterance) {
        nonisolated(unsafe) let utterance = utterance
        Task { @MainActor in self.finished(utterance) }
    }

    nonisolated func speechSynthesizer(_ synthesizer: AVSpeechSynthesizer, didCancel utterance: AVSpeechUtterance) {
        nonisolated(unsafe) let utterance = utterance
        Task { @MainActor in self.finished(utterance) }
    }
}
