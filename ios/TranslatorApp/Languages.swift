import Foundation

/// `englishName` is what the prompts use. `scriptHint` is appended to prompts when the language has
/// more than one common script. `voiceLocale` is requested from the system voices; nil = no voice.
/// `speakWord` ("speak" in that language) is shown on the coworker's button.
struct Language: Hashable, Identifiable, Sendable {
    let code: String
    let spanishName: String
    let englishName: String
    let nativeName: String
    let voiceLocale: String?
    let speakWord: String
    let scriptHint: String?

    init(_ code: String, _ spanishName: String, _ englishName: String, _ nativeName: String,
         _ voiceLocale: String?, _ speakWord: String, _ scriptHint: String? = nil) {
        self.code = code
        self.spanishName = spanishName
        self.englishName = englishName
        self.nativeName = nativeName
        self.voiceLocale = voiceLocale
        self.speakWord = speakWord
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
        // When two list entries share a voice language (Punjabi India/Pakistan), only the exact region counts.
        let shared = (Languages.all + [Languages.spanish])
            .filter { $0.voiceLocale.map { splitTag($0).language } == wanted.language }.count > 1
        return shared ? nil : 0
    }
}

private func splitTag(_ tag: String) -> (language: String, region: String) {
    let parts = tag.split(whereSeparator: { $0 == "-" || $0 == "_" })
    return (parts.first.map { $0.lowercased() } ?? "", parts.count > 1 ? parts[parts.count - 1].uppercased() : "")
}

enum Languages {
    /// Walter's language. Fixed; not in the coworker list.
    static let spanish = Language("es", "Español", "Spanish", "Español", "es-ES", "Habla")

    static let all: [Language] = [
        Language("ur", "Urdu", "Urdu", "اردو", "ur-PK", "بولیں"),
        Language("pa-Arab", "Panyabí (Pakistán)", "Punjabi", "پنجابی", "pa-PK", "بولو",
                 "Write Punjabi in Shahmukhi (Perso-Arabic) script, as used in Pakistan."),
        Language("pa", "Panyabí (India)", "Punjabi", "ਪੰਜਾਬੀ", "pa-IN", "ਬੋਲੋ", "Write Punjabi in Gurmukhi script."),
        Language("ps", "Pastún", "Pashto", "پښتو", "ps-AF", "خبرې وکړه"),
        Language("sd", "Sindi", "Sindhi", "سنڌي", "sd-PK", "ڳالهايو", "Write Sindhi in Arabic script."),
        Language("hi", "Hindi", "Hindi", "हिन्दी", "hi-IN", "बोलिए"),
        Language("bn", "Bengalí", "Bengali", "বাংলা", "bn-BD", "বলুন"),
        Language("ne", "Nepalí", "Nepali", "नेपाली", "ne-NP", "बोल्नुहोस्"),
        Language("fa", "Persa / Dari", "Persian", "فارسی", "fa-IR", "صحبت کن"),
        Language("ar", "Árabe", "Arabic", "العربية", "ar-SA", "تكلم", "Use Modern Standard Arabic."),
        Language("ary", "Árabe marroquí (dariya)", "Moroccan Arabic (Darija)", "الدارجة", "ar-MA", "هضر"),
        Language("wo", "Wolof", "Wolof", "Wolof", nil, "Wax"),
        Language("bm", "Bambara", "Bambara", "Bamanankan", nil, "Kuma"),
        Language("ha", "Hausa", "Hausa", "Hausa", nil, "Yi magana"),
        Language("sw", "Suajili", "Swahili", "Kiswahili", "sw-KE", "Ongea"),
        Language("am", "Amárico", "Amharic", "አማርኛ", "am-ET", "ተናገር"),
        Language("fr", "Francés", "French", "Français", "fr-FR", "Parle"),
        Language("en", "Inglés", "English", "English", "en-GB", "Speak"),
        Language("ro", "Rumano", "Romanian", "Română", "ro-RO", "Vorbește"),
        Language("bg", "Búlgaro", "Bulgarian", "Български", "bg-BG", "Говори"),
        Language("uk", "Ucraniano", "Ukrainian", "Українська", "uk-UA", "Говори"),
        Language("ru", "Ruso", "Russian", "Русский", "ru-RU", "Говори"),
        Language("pl", "Polaco", "Polish", "Polski", "pl-PL", "Mów"),
        Language("pt", "Portugués", "Portuguese", "Português", "pt-PT", "Fala"),
        Language("it", "Italiano", "Italian", "Italiano", "it-IT", "Parla"),
        Language("de", "Alemán", "German", "Deutsch", "de-DE", "Sprich"),
        Language("nl", "Neerlandés", "Dutch", "Nederlands", "nl-NL", "Spreek"),
        Language("ca", "Catalán", "Catalan", "Català", "ca-ES", "Parla"),
        Language("sq", "Albanés", "Albanian", "Shqip", "sq-AL", "Fol"),
        Language("ka", "Georgiano", "Georgian", "ქართული", "ka-GE", "ილაპარაკე"),
        Language("tr", "Turco", "Turkish", "Türkçe", "tr-TR", "Konuş"),
        Language("zh", "Chino", "Chinese", "简体中文", "zh-CN", "请说", "Use Simplified Chinese characters."),
        Language("ja", "Japonés", "Japanese", "日本語", "ja-JP", "話して"),
        Language("ko", "Coreano", "Korean", "한국어", "ko-KR", "말하세요"),
        Language("vi", "Vietnamita", "Vietnamese", "Tiếng Việt", "vi-VN", "Nói"),
        Language("th", "Tailandés", "Thai", "ไทย", "th-TH", "พูด"),
        Language("id", "Indonesio", "Indonesian", "Bahasa Indonesia", "id-ID", "Bicara"),
        Language("tl", "Filipino (tagalo)", "Filipino", "Filipino", "fil-PH", "Magsalita"),
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
