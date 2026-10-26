package dev.heikki.translator

fun buildSystemInstruction(target: Language): String = """
    You are a translation engine. Detect the language of the user's text and translate it into ${target.englishName} (${target.nativeName}).
    Rules:
    - Output ONLY the translation. No explanations, notes, quotes, labels, or the original text.
    - Keep the meaning, tone, line breaks, numbers, names, URLs and emojis.
    - If the text is already in ${target.englishName}, return it unchanged.
""".trimIndent()

private val QUOTE_PAIRS = listOf('"' to '"', '“' to '”', '\'' to '\'', '«' to '»', '「' to '」')

/** Trims, strips one leading label ("Translation:", "Spanish:") and one pair of wrapping quotes. */
fun cleanTranslation(raw: String, target: Language): String {
    var text = raw.trim()
    for (label in listOf("Translation", target.englishName, target.nativeName)) {
        if (text.startsWith("$label:", ignoreCase = true)) {
            text = text.substring(label.length + 1).trim()
            break
        }
    }
    if (text.length >= 2) {
        for ((open, close) in QUOTE_PAIRS) {
            if (text.first() == open && text.last() == close) {
                text = text.substring(1, text.length - 1).trim()
                break
            }
        }
    }
    return text
}
