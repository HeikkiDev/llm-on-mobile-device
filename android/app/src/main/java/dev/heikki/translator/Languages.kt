package dev.heikki.translator

import java.text.Normalizer
import java.util.Locale

data class Language(val code: String, val englishName: String, val nativeName: String)

object Languages {
    val all = listOf(
        Language("en", "English", "English"),
        Language("es", "Spanish", "Español"),
        Language("fr", "French", "Français"),
        Language("de", "German", "Deutsch"),
        Language("it", "Italian", "Italiano"),
        Language("pt", "Portuguese", "Português"),
        Language("nl", "Dutch", "Nederlands"),
        Language("ca", "Catalan", "Català"),
        Language("pl", "Polish", "Polski"),
        Language("ru", "Russian", "Русский"),
        Language("uk", "Ukrainian", "Українська"),
        Language("tr", "Turkish", "Türkçe"),
        Language("ar", "Arabic", "العربية"),
        Language("hi", "Hindi", "हिन्दी"),
        Language("zh", "Chinese (Simplified)", "简体中文"),
        Language("ja", "Japanese", "日本語"),
        Language("ko", "Korean", "한국어"),
    )

    fun byCode(code: String?): Language? = all.firstOrNull { it.code == code }

    /** Case- and accent-insensitive match on English name, native name or code. */
    fun search(query: String): List<Language> {
        val q = fold(query.trim())
        if (q.isEmpty()) return all
        return all.filter { l -> fold(l.englishName).contains(q) || fold(l.nativeName).contains(q) || l.code == q }
    }

    private fun fold(text: String): String =
        Normalizer.normalize(text, Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "").lowercase(Locale.ROOT)

    /** English, unless the device already uses English; then Spanish. */
    fun default(locale: Locale = Locale.getDefault()): Language =
        byCode(if (locale.language == "en") "es" else "en")!!
}
