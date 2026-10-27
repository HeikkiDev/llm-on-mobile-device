import Foundation

struct Language: Hashable, Identifiable, Sendable {
    let code: String
    let englishName: String
    let nativeName: String

    var id: String { code }
}

enum Languages {
    static let all: [Language] = [
        Language(code: "en", englishName: "English", nativeName: "English"),
        Language(code: "es", englishName: "Spanish", nativeName: "Español"),
        Language(code: "fr", englishName: "French", nativeName: "Français"),
        Language(code: "de", englishName: "German", nativeName: "Deutsch"),
        Language(code: "it", englishName: "Italian", nativeName: "Italiano"),
        Language(code: "pt", englishName: "Portuguese", nativeName: "Português"),
        Language(code: "nl", englishName: "Dutch", nativeName: "Nederlands"),
        Language(code: "ca", englishName: "Catalan", nativeName: "Català"),
        Language(code: "pl", englishName: "Polish", nativeName: "Polski"),
        Language(code: "ru", englishName: "Russian", nativeName: "Русский"),
        Language(code: "uk", englishName: "Ukrainian", nativeName: "Українська"),
        Language(code: "tr", englishName: "Turkish", nativeName: "Türkçe"),
        Language(code: "ar", englishName: "Arabic", nativeName: "العربية"),
        Language(code: "hi", englishName: "Hindi", nativeName: "हिन्दी"),
        Language(code: "zh", englishName: "Chinese (Simplified)", nativeName: "简体中文"),
        Language(code: "ja", englishName: "Japanese", nativeName: "日本語"),
        Language(code: "ko", englishName: "Korean", nativeName: "한국어"),
    ]

    static func byCode(_ code: String?) -> Language? {
        all.first { $0.code == code }
    }

    /// Case- and accent-insensitive match on English name, native name or code.
    static func search(_ query: String) -> [Language] {
        let q = fold(query.trimmingCharacters(in: .whitespacesAndNewlines))
        if q.isEmpty { return all }
        return all.filter { fold($0.englishName).contains(q) || fold($0.nativeName).contains(q) || $0.code == q }
    }

    private static func fold(_ text: String) -> String {
        text.folding(options: [.caseInsensitive, .diacriticInsensitive], locale: nil)
    }

    /// English, unless the device already uses English; then Spanish.
    static func defaultLanguage(for locale: Locale = .current) -> Language {
        byCode(locale.language.languageCode == .english ? "es" : "en")!
    }
}
