import Foundation

// Prompts (SPEC-WALTER §3.3). Languages are named in English, which the model follows best.

let speechSystemInstruction =
    "You are a speech translation engine. Follow the requested output format exactly. Never add explanations."

let detectionSystemInstruction = "You identify spoken languages. Answer with a single code."

/// Prompt A: the Gemma 4 model card's speech-translation (AST) template. The audio goes after this text.
func buildSpeechPrompt(source: Language, target: Language) -> String {
    var prompt = """
    Transcribe the following speech segment in \(source.englishName), then translate it into \(target.englishName).
    When formatting the answer, first output the transcription in \(source.englishName), then one newline, \
    then output the string '\(target.englishName): ', then the translation in \(target.englishName).
    """
    for hint in [source.scriptHint, target.scriptHint].compactMap({ $0 }) { prompt += "\n" + hint }
    return prompt
}

/// Prompt B: language identification. The audio goes after this text.
func buildDetectionPrompt(_ candidates: [Language] = Languages.all) -> String {
    """
    Identify the language spoken in this audio.
    Answer with ONLY one code from this list, nothing else: \(candidates.map(\.code).joined(separator: ", ")).
    If the language is not in the list, answer: unknown
    """
}

/// Prompt C: typed text, either direction between Spanish and the coworker's language.
func buildTextSystemInstruction(_ coworker: Language) -> String {
    var instruction = """
    You are a translation engine. If the user's text is in Spanish, translate it into \(coworker.englishName). \
    Otherwise, translate it into Spanish.
    Rules:
    - Output ONLY the translation. No explanations, notes, quotes, labels, or the original text.
    - Keep the meaning, tone, line breaks, numbers, names and emojis.
    """
    if let hint = coworker.scriptHint { instruction += "\n- When writing \(coworker.englishName): " + hint }
    return instruction
}

struct SpeechTranslation: Equatable {
    let transcript: String
    let translation: String
}

/// Splits "transcript\nTarget: translation". While streaming (`isFinal` false) text before the first
/// newline is still the transcript; once final, an output without a newline or label is all translation.
func parseSpeechTranslation(_ raw: String, target: Language, isFinal: Bool = true) -> SpeechTranslation {
    let text = raw.trimmingCharacters(in: .whitespacesAndNewlines)
    for label in labels(for: target) {
        if let range = text.range(of: label + ":", options: .caseInsensitive) {
            return SpeechTranslation(
                transcript: text[..<range.lowerBound].trimmingCharacters(in: .whitespacesAndNewlines),
                translation: cleanTranslation(String(text[range.upperBound...]), target: target)
            )
        }
    }
    if let newline = text.firstIndex(of: "\n") {
        return SpeechTranslation(
            transcript: text[..<newline].trimmingCharacters(in: .whitespacesAndNewlines),
            translation: cleanTranslation(String(text[text.index(after: newline)...]), target: target)
        )
    }
    return isFinal
        ? SpeechTranslation(transcript: "", translation: cleanTranslation(text, target: target))
        : SpeechTranslation(transcript: text, translation: "")
}

private let answerSeparators = Set(",.;:!?\"'`*()[]")

/// First token of the answer, matched against the candidates' codes (or English names).
func parseDetectedLanguage(_ raw: String, candidates: [Language] = Languages.all) -> Language? {
    let token = raw.lowercased()
        .split(whereSeparator: { $0.isWhitespace || answerSeparators.contains($0) })
        .first.map(String.init)
    guard let token else { return nil }
    return candidates.first { $0.code.lowercased() == token }
        ?? candidates.first { $0.englishName.lowercased() == token }
}

private let quotePairs: [(Character, Character)] = [("\"", "\""), ("“", "”"), ("'", "'"), ("«", "»"), ("「", "」")]

private func labels(for target: Language) -> [String] {
    var labels: [String] = []
    for label in [target.englishName, target.spanishName, target.nativeName] where !labels.contains(label) {
        labels.append(label)
    }
    return labels
}

/// Trims, strips one leading label ("Translation:", "Spanish:") and one pair of wrapping quotes.
func cleanTranslation(_ raw: String, target: Language) -> String {
    var text = raw.trimmingCharacters(in: .whitespacesAndNewlines)
    for label in ["Translation"] + labels(for: target) {
        let prefix = label + ":"
        if text.range(of: prefix, options: [.anchored, .caseInsensitive]) != nil {
            text = String(text.dropFirst(prefix.count)).trimmingCharacters(in: .whitespacesAndNewlines)
            break
        }
    }
    if text.count >= 2, let first = text.first, let last = text.last {
        for (open, close) in quotePairs where first == open && last == close {
            text = String(text.dropFirst().dropLast()).trimmingCharacters(in: .whitespacesAndNewlines)
            break
        }
    }
    return text
}

/// Longest output we accept for one turn; anything longer is a runaway generation.
let maxOutputChars = 1500

/// True when the model is stuck: output far too long, or the last 80 characters are one short chunk
/// (≤ 20 chars) repeated, e.g. "لاء لاء لاء …". The caller then stops the generation.
func isRunaway(_ text: String) -> Bool {
    let count = text.count
    if count > maxOutputChars { return true }
    if count < 80 { return false }
    let tail = Array(text.suffix(80))
    return (1...20).contains { p in (p..<tail.count).allSatisfy { tail[$0] == tail[$0 - p] } }
}

private let spanishWords: Set<String> = [
    "el", "la", "los", "las", "de", "que", "y", "en", "un", "una", "es", "por", "para", "con", "no", "se", "lo",
    "más", "pero", "muy", "está", "aquí", "eso", "esto", "yo", "tú", "favor", "dónde", "qué", "cómo",
]

/// Rough "how Spanish does this look" score: Spanish stop-words plus ñ/¿/¡. Cosmetic only (§3.3 C).
func spanishScore(_ text: String) -> Int {
    let words = text.lowercased().split(whereSeparator: { !$0.isLetter })
    return words.filter { spanishWords.contains(String($0)) }.count + text.filter { "ñÑ¿¡".contains($0) }.count
}

/// Typed mode: the coworker typed it when the translation looks more Spanish than the input.
func typedByCoworker(input: String, output: String) -> Bool {
    spanishScore(output) > spanishScore(input)
}
