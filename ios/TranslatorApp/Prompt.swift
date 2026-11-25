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
/// Leaves text alone when the `source` had the same label or was itself quoted, and keeps quotes
/// that also appear inside (`"Yes" or "No"`).
func cleanTranslation(_ raw: String, target: Language, source: String = "") -> String {
    let source = source.trimmingCharacters(in: .whitespacesAndNewlines)
    var text = raw.trimmingCharacters(in: .whitespacesAndNewlines)
    for label in ["Translation", target.englishName, target.nativeName] {
        let prefix = label + ":"
        if text.range(of: prefix, options: [.anchored, .caseInsensitive]) != nil {
            if source.range(of: prefix, options: [.anchored, .caseInsensitive]) == nil {
                text = String(text.dropFirst(prefix.count)).trimmingCharacters(in: .whitespacesAndNewlines)
            }
            break
        }
    }
    if text.count >= 2, let first = text.first, let last = text.last {
        let sourceQuoted = source.count >= 2
            && quotePairs.contains { source.first == $0.0 && source.last == $0.1 }
        for (open, close) in quotePairs where first == open && last == close {
            let inner = String(text.dropFirst().dropLast())
            if !sourceQuoted && !inner.contains(open) && !inner.contains(close) {
                text = inner.trimmingCharacters(in: .whitespacesAndNewlines)
            }
            break
        }
    }
    return text
}
