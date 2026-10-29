package dev.heikki.translator

import java.text.Normalizer
import java.util.Locale

/**
 * [englishName] is what the prompts use. [scriptHint] is appended to prompts when the language has
 * more than one common script. [voiceLocale] is requested from the system TTS engine; null = no voice.
 * [speakWord] ("speak" in that language) is shown on the coworker's button.
 */
data class Language(
    val code: String,
    val spanishName: String,
    val englishName: String,
    val nativeName: String,
    val voiceLocale: String?,
    val speakWord: String,
    val scriptHint: String? = null,
)

object Languages {
    /** Walter's language. Fixed; not in the coworker list. */
    val spanish = Language("es", "Español", "Spanish", "Español", "es-ES", "Habla")

    val all = listOf(
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
        Language("wo", "Wolof", "Wolof", "Wolof", null, "Wax"),
        Language("bm", "Bambara", "Bambara", "Bamanankan", null, "Kuma"),
        Language("ha", "Hausa", "Hausa", "Hausa", null, "Yi magana"),
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
