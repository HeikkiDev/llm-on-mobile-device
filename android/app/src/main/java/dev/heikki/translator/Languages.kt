package dev.heikki.translator

import java.text.Normalizer
import java.util.Locale

/**
 * [englishName] is what the prompts use. [scriptHint] is appended to prompts when the language has
 * more than one common script. [voiceLocale] is requested from the system TTS engine; null = no voice.
 */
data class Language(
    val code: String,
    val spanishName: String,
    val englishName: String,
    val nativeName: String,
    val voiceLocale: String?,
    val scriptHint: String? = null,
)

object Languages {
    /** Walter's language. Fixed; not in the coworker list. */
    val spanish = Language("es", "Español", "Spanish", "Español", "es-ES")

    val all = listOf(
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
        Language("wo", "Wolof", "Wolof", "Wolof", null),
        Language("bm", "Bambara", "Bambara", "Bamanankan", null),
        Language("ha", "Hausa", "Hausa", "Hausa", null),
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
    )

    fun byCode(code: String?): Language? = all.firstOrNull { it.code == code }

    /** Alphabetical by Spanish name, accent-insensitive. */
    val alphabetical: List<Language> = all.sortedBy { fold(it.spanishName) }

    /** Case- and accent-insensitive match on Spanish, English or native name, or exact code. */
    fun search(query: String): List<Language> {
        val q = fold(query.trim())
        if (q.isEmpty()) return alphabetical
        return alphabetical.filter { l ->
            fold(l.spanishName).contains(q) || fold(l.englishName).contains(q) ||
                fold(l.nativeName).contains(q) || l.code.lowercase(Locale.ROOT) == q
        }
    }

    private val RELATED = listOf(
        listOf("ur", "hi", "pa-Arab", "pa", "sd"),
        listOf("ps", "fa", "ur"),
        listOf("ar", "ary"),
        listOf("ro", "it", "pt", "ca", "fr"),
        listOf("uk", "ru", "bg", "pl"),
    )

    /** Languages easily confused with [language] by ear (shown first after a detection, §5.3), itself first. */
    fun related(language: Language): List<Language> =
        (listOf(language.code) + RELATED.filter { language.code in it }.flatten())
            .distinct().mapNotNull { byCode(it) }

    /** Most recent first, no duplicates, at most [MAX_RECENTS]. */
    fun pushRecent(recents: List<String>, code: String): List<String> =
        (listOf(code) + recents.filter { it != code }).take(MAX_RECENTS)

    fun decodeRecents(stored: String?): List<String> =
        stored.orEmpty().split(',').filter { byCode(it) != null }.distinct().take(MAX_RECENTS)

    fun encodeRecents(codes: List<String>): String = codes.joinToString(",")

    fun fold(text: String): String =
        Normalizer.normalize(text, Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "").lowercase(Locale.ROOT)

    const val MAX_RECENTS = 5
}
