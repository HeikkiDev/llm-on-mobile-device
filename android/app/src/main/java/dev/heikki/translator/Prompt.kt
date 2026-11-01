package dev.heikki.translator

import java.util.Locale

// Prompts (SPEC-WALTER §3.3). Languages are named in English, which the model follows best.

const val SPEECH_SYSTEM_INSTRUCTION =
    "You are a speech translation engine. Follow the requested output format exactly. Never add explanations."

const val DETECTION_SYSTEM_INSTRUCTION = "You identify spoken languages. Answer with a single code."

/** Prompt A: the Gemma 4 model card's speech-translation (AST) template. The audio goes after this text. */
fun buildSpeechPrompt(source: Language, target: Language): String = buildString {
    append("Transcribe the following speech segment in ${source.englishName}, then translate it into ${target.englishName}.\n")
    append("When formatting the answer, first output the transcription in ${source.englishName}, then one newline, ")
    append("then output the string '${target.englishName}: ', then the translation in ${target.englishName}.")
    listOfNotNull(source.scriptHint, target.scriptHint).forEach { append("\n").append(it) }
}

/** Prompt A1: the model card's speech-recognition (ASR) template. The audio goes after this text. */
fun buildTranscriptionPrompt(source: Language): String =
    "Transcribe the following speech segment in ${source.englishName} into ${source.englishName} text.\n" +
        "Follow these specific instructions for formatting the answer:\n" +
        "* Only output the transcription, with no newlines.\n" +
        "* When transcribing numbers, write the digits, i.e. write 1.7 and not one point seven, and write 3 instead of three."

/** Prompt B: language identification. The audio goes after this text. */
fun buildDetectionPrompt(candidates: List<Language> = Languages.all): String =
    "Identify the language spoken in this audio.\n" +
        "Answer with ONLY one code from this list, nothing else: ${candidates.joinToString(", ") { it.code }}.\n" +
        "If the language is not in the list, answer: unknown"

/** Prompt C: typed text, either direction between Spanish and the coworker's language. */
fun buildTextSystemInstruction(coworker: Language): String = buildString {
    append("You are a translation engine. If the user's text is in Spanish, translate it into ${coworker.englishName}. ")
    append("Otherwise, translate it into Spanish.\n")
    append("Rules:\n")
    append("- Output ONLY the translation. No explanations, notes, quotes, labels, or the original text.\n")
    append("- Keep the meaning, tone, line breaks, numbers, names and emojis.")
    coworker.scriptHint?.let { append("\n- When writing ${coworker.englishName}: ").append(it) }
}

data class SpeechTranslation(val transcript: String, val translation: String)

/**
 * Splits "transcript\nTarget: translation". While streaming ([final] = false) text before the first
 * newline is still the transcript; once final, an output without a newline or label is all translation.
 */
fun parseSpeechTranslation(raw: String, target: Language, final: Boolean = true): SpeechTranslation {
    val text = raw.trim()
    labelsFor(target).forEach { label ->
        val i = text.indexOf("$label:", ignoreCase = true)
        if (i >= 0) {
            return SpeechTranslation(
                transcript = text.substring(0, i).trim(),
                translation = cleanTranslation(text.substring(i + label.length + 1), target),
            )
        }
    }
    val newline = text.indexOf('\n')
    return when {
        newline >= 0 -> SpeechTranslation(text.substring(0, newline).trim(), cleanTranslation(text.substring(newline + 1), target))
        final -> SpeechTranslation("", cleanTranslation(text, target))
        else -> SpeechTranslation(text, "")
    }
}

/** First token of the answer, matched against the candidates' codes (or English names). */
fun parseDetectedLanguage(raw: String, candidates: List<Language> = Languages.all): Language? {
    val token = raw.trim().lowercase(Locale.ROOT)
        .split(Regex("[\\s,.;:!?\"'`*()\\[\\]]+")).firstOrNull { it.isNotEmpty() } ?: return null
    return candidates.firstOrNull { it.code.lowercase(Locale.ROOT) == token }
        ?: candidates.firstOrNull { it.englishName.lowercase(Locale.ROOT) == token }
}

private val QUOTE_PAIRS = listOf('"' to '"', '“' to '”', '\'' to '\'', '«' to '»', '「' to '」')

private fun labelsFor(target: Language) =
    listOf(target.englishName, target.spanishName, target.nativeName).distinct()

/** Trims, strips one leading label ("Translation:", "Spanish:") and one pair of wrapping quotes. */
fun cleanTranslation(raw: String, target: Language): String {
    var text = raw.trim()
    for (label in listOf("Translation") + labelsFor(target)) {
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

/** Longest output we accept for one turn; anything longer is a runaway generation. */
const val MAX_OUTPUT_CHARS = 1500

/**
 * True when the model is stuck: output far too long, or the last 80 characters are one short chunk
 * (≤ 20 chars) repeated, e.g. "لاء لاء لاء …". The caller then stops the generation.
 */
fun isRunaway(text: CharSequence): Boolean {
    if (text.length > MAX_OUTPUT_CHARS) return true
    if (text.length < 80) return false
    val tail = text.subSequence(text.length - 80, text.length)
    return (1..20).any { p -> (p until tail.length).all { tail[it] == tail[it - p] } }
}

private val SPANISH_WORDS = setOf(
    "el", "la", "los", "las", "de", "que", "y", "en", "un", "una", "es", "por", "para", "con", "no", "se", "lo",
    "más", "pero", "muy", "está", "aquí", "eso", "esto", "yo", "tú", "favor", "dónde", "qué", "cómo",
)

/** Rough "how Spanish does this look" score: Spanish stop-words plus ñ/¿/¡. Cosmetic only (§3.3 C). */
fun spanishScore(text: String): Int {
    val words = text.lowercase(Locale.ROOT).split(Regex("[^\\p{L}]+")).filter { it.isNotEmpty() }
    return words.count { it in SPANISH_WORDS } + text.count { it in "ñÑ¿¡" }
}

/** Typed mode: the coworker typed it when the translation looks more Spanish than the input. */
fun typedByCoworker(input: String, output: String): Boolean = spanishScore(output) > spanishScore(input)
