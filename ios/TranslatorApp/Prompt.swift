import Foundation

func buildSystemInstruction(_ target: Language) -> String {
    """
    You are a translation engine. Detect the language of the user's text and translate it into \(target.englishName) (\(target.nativeName)).
    Rules:
    - Output ONLY the translation. No explanations, notes, quotes, labels, or the original text.
    - Keep the meaning, tone, line breaks, numbers, names, URLs and emojis.
    - If the text is already in \(target.englishName), return it unchanged.
    """
}

private let quotePairs: [(Character, Character)] = [("\"", "\""), ("“", "”"), ("'", "'"), ("«", "»"), ("「", "」")]

/// Trims, strips one leading label ("Translation:", "Spanish:") and one pair of wrapping quotes.
func cleanTranslation(_ raw: String, target: Language) -> String {
    var text = raw.trimmingCharacters(in: .whitespacesAndNewlines)
    for label in ["Translation", target.englishName, target.nativeName] {
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
