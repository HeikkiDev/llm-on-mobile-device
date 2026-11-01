import Foundation

/// `englishName` is what the prompts use. `scriptHint` is appended to prompts when the language has
/// more than one common script. `voiceLocale` is requested from the system voices; nil = no voice.
struct Language: Hashable, Identifiable, Sendable {
    let code: String
    let spanishName: String
    let englishName: String
    let nativeName: String
    let voiceLocale: String?
    let scriptHint: String?

    init(_ code: String, _ spanishName: String, _ englishName: String, _ nativeName: String,
         _ voiceLocale: String?, _ scriptHint: String? = nil) {
        self.code = code
        self.spanishName = spanishName
        self.englishName = englishName
        self.nativeName = nativeName
        self.voiceLocale = voiceLocale
        self.scriptHint = scriptHint
    }

    var id: String { code }

    /// How well a system voice tagged `voiceLanguage` (e.g. "ur-PK") fits: nil = it can't speak this
    /// language, 1 = same region, 0 = same language, other region.
    func voiceFit(_ voiceLanguage: String) -> Int? {
        guard let voiceLocale else { return nil }
        let wanted = splitTag(voiceLocale)
        let voice = splitTag(voiceLanguage)
        guard wanted.language == voice.language else { return nil }
        if wanted.region == voice.region { return 1 }
        // Punjabi India/Pakistan are different languages in practice: only the exact region counts.
        // Everything else (Arabic voices are ar-001, ar-XA…) accepts any region.
        return wanted.language == "pa" ? nil : 0
    }
}

private func splitTag(_ tag: String) -> (language: String, region: String) {
    let parts = tag.split(whereSeparator: { $0 == "-" || $0 == "_" })
    return (parts.first.map { $0.lowercased() } ?? "", parts.count > 1 ? parts[parts.count - 1].uppercased() : "")
}

enum Languages {
    /// Walter's language. Fixed; not in the coworker list.
    static let spanish = Language("es", "Español", "Spanish", "Español", "es-ES")

    static let all: [Language] = [
        Language("ur", "Urdu", "Urdu", "اردو", "ur-PK"),
        Language("pa-Arab", "Panyabí (Pakistán)", "Punjabi", "پنجابی", "pa-PK",
                 "Write Punjabi in Shahmukhi (Perso-Arabic) script, as used in Pakistan."),
        Language("pa", "Panyabí (India)", "Punjabi", "ਪੰਜਾਬੀ", "pa-IN", "Write Punjabi in Gurmukhi script."),
        Language("ps", "Pastún", "Pashto", "پښتو", "ps-AF"),
        Language("sd", "Sindi", "Sindhi", "سنڌي", "sd-PK", "Write Sindhi in Arabic script."),
        Language("hi", "Hindi", "Hindi", "हिन्दी", "hi-IN"),
        Language("bn", "Bengalí", "Bengali", "বাংলা", "bn-BD"),
        Language("ne", "Nepalí", "Nepali", "नेपाली", "ne-NP"),
        Language("fa", "Persa / Dari", "Persian", "فارسی", "fa-IR"),
        Language("ar", "Árabe", "Arabic", "العربية", "ar-SA", "Use Modern Standard Arabic."),
        Language("ary", "Árabe marroquí (dariya)", "Moroccan Arabic (Darija)", "الدارجة", "ar-MA"),
        Language("wo", "Wolof", "Wolof", "Wolof", nil),
        Language("bm", "Bambara", "Bambara", "Bamanankan", nil),
        Language("ha", "Hausa", "Hausa", "Hausa", nil),
        Language("sw", "Suajili", "Swahili", "Kiswahili", "sw-KE"),
        Language("am", "Amárico", "Amharic", "አማርኛ", "am-ET"),
        Language("fr", "Francés", "French", "Français", "fr-FR"),
        Language("en", "Inglés", "English", "English", "en-GB"),
        Language("ro", "Rumano", "Romanian", "Română", "ro-RO"),
        Language("bg", "Búlgaro", "Bulgarian", "Български", "bg-BG"),
        Language("uk", "Ucraniano", "Ukrainian", "Українська", "uk-UA"),
        Language("ru", "Ruso", "Russian", "Русский", "ru-RU"),
        Language("pl", "Polaco", "Polish", "Polski", "pl-PL"),
        Language("pt", "Portugués", "Portuguese", "Português", "pt-PT"),
        Language("it", "Italiano", "Italian", "Italiano", "it-IT"),
        Language("de", "Alemán", "German", "Deutsch", "de-DE"),
        Language("nl", "Neerlandés", "Dutch", "Nederlands", "nl-NL"),
        Language("ca", "Catalán", "Catalan", "Català", "ca-ES"),
        Language("sq", "Albanés", "Albanian", "Shqip", "sq-AL"),
        Language("ka", "Georgiano", "Georgian", "ქართული", "ka-GE"),
        Language("tr", "Turco", "Turkish", "Türkçe", "tr-TR"),
        Language("zh", "Chino", "Chinese", "简体中文", "zh-CN", "Use Simplified Chinese characters."),
        Language("ja", "Japonés", "Japanese", "日本語", "ja-JP"),
        Language("ko", "Coreano", "Korean", "한국어", "ko-KR"),
        Language("vi", "Vietnamita", "Vietnamese", "Tiếng Việt", "vi-VN"),
        Language("th", "Tailandés", "Thai", "ไทย", "th-TH"),
        Language("id", "Indonesio", "Indonesian", "Bahasa Indonesia", "id-ID"),
        Language("tl", "Filipino (tagalo)", "Filipino", "Filipino", "fil-PH"),
    ]

    static let maxRecents = 5

    static func byCode(_ code: String?) -> Language? {
        all.first { $0.code == code }
    }

    /// Alphabetical by Spanish name, accent-insensitive.
    static let alphabetical: [Language] = all.sorted { fold($0.spanishName) < fold($1.spanishName) }

    /// Case- and accent-insensitive match on Spanish, English or native name, or exact code.
    static func search(_ query: String) -> [Language] {
        let q = fold(query.trimmingCharacters(in: .whitespacesAndNewlines))
        if q.isEmpty { return alphabetical }
        return alphabetical.filter {
            fold($0.spanishName).contains(q) || fold($0.englishName).contains(q) ||
                fold($0.nativeName).contains(q) || $0.code.lowercased() == q
        }
    }

    private static let relatedGroups = [
        ["ur", "hi", "pa-Arab", "pa", "sd"],
        ["ps", "fa", "ur"],
        ["ar", "ary"],
        ["ro", "it", "pt", "ca", "fr"],
        ["uk", "ru", "bg", "pl"],
    ]

    /// Languages easily confused with `language` by ear (shown first after a detection, §5.3), itself first.
    static func related(_ language: Language) -> [Language] {
        var codes = [language.code]
        for code in relatedGroups.filter({ $0.contains(language.code) }).joined() where !codes.contains(code) {
            codes.append(code)
        }
        return codes.compactMap { byCode($0) }
    }

    /// Most recent first, no duplicates, at most `maxRecents`.
    static func pushRecent(_ recents: [String], _ code: String) -> [String] {
        Array(([code] + recents.filter { $0 != code }).prefix(maxRecents))
    }

    static func decodeRecents(_ stored: String?) -> [String] {
        var codes: [String] = []
        for code in (stored ?? "").split(separator: ",").map(String.init)
        where byCode(code) != nil && !codes.contains(code) {
            codes.append(code)
        }
        return Array(codes.prefix(maxRecents))
    }

    static func encodeRecents(_ codes: [String]) -> String {
        codes.joined(separator: ",")
    }

    static func fold(_ text: String) -> String {
        text.folding(options: [.caseInsensitive, .diacriticInsensitive], locale: nil)
    }
}
