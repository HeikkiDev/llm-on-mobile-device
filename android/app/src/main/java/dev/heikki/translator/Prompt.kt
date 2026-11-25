package dev.heikki.translator

fun buildSystemInstruction(target: Language): String = """
    You are a translation engine. Detect the language of the user's text and translate it into ${target.englishName} (${target.nativeName}).
    Rules:
    - Output ONLY the translation. No explanations, notes, quotes, labels, or the original text.
    - Keep the meaning, tone, line breaks, numbers, names, URLs and emojis.
    - If the text is already in ${target.englishName}, return it unchanged.
""".trimIndent()

private val QUOTE_PAIRS = listOf('"' to '"', '“' to '”', '\'' to '\'', '«' to '»', '「' to '」')

/**
 * Trims, strips one leading label ("Translation:", "Spanish:") and one pair of wrapping quotes.
 * Leaves text alone when the [source] had the same label or was itself quoted, and keeps quotes
 * that also appear inside (`"Yes" or "No"`).
 */
fun cleanTranslation(raw: String, target: Language, source: String = ""): String {
    val src = source.trim()
    var text = raw.trim()
    for (label in listOf("Translation", target.englishName, target.nativeName)) {
        val prefix = "$label:"
        if (text.startsWith(prefix, ignoreCase = true)) {
            if (!src.startsWith(prefix, ignoreCase = true)) text = text.substring(prefix.length).trim()
            break
        }
    }
    if (text.length >= 2) {
        val sourceQuoted = src.length >= 2 && QUOTE_PAIRS.any { (open, close) -> src.first() == open && src.last() == close }
        for ((open, close) in QUOTE_PAIRS) {
            if (text.first() == open && text.last() == close) {
                val inner = text.substring(1, text.length - 1)
                if (!sourceQuoted && open !in inner && close !in inner) text = inner.trim()
                break
            }
        }
    }
    return text
}
