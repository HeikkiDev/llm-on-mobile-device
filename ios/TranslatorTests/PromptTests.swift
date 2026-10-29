import Foundation
import Testing
@testable import Translator

struct PromptTests {
    private let spanish = Languages.spanish
    private let urdu = Languages.byCode("ur")!
    private let punjabiPk = Languages.byCode("pa-Arab")!

    @Test func speechPromptUsesAstTemplateWithEnglishNames() {
        let prompt = buildSpeechPrompt(source: spanish, target: urdu)
        #expect(prompt.hasPrefix("Transcribe the following speech segment in Spanish, then translate it into Urdu."))
        #expect(prompt.contains("then output the string 'Urdu: ', then the translation in Urdu."))
    }

    @Test func speechPromptAddsScriptHint() {
        #expect(buildSpeechPrompt(source: punjabiPk, target: spanish).contains("Shahmukhi"))
        #expect(buildTextSystemInstruction(punjabiPk).contains("Shahmukhi"))
    }

    @Test func detectionPromptListsCodes() {
        let prompt = buildDetectionPrompt([urdu, spanish])
        #expect(prompt.contains("nothing else: ur, es."))
        #expect(prompt.contains("answer: unknown"))
    }

    @Test func textInstructionIsBidirectional() {
        let instruction = buildTextSystemInstruction(urdu)
        #expect(instruction.contains("If the user's text is in Spanish, translate it into Urdu."))
        #expect(instruction.contains("Otherwise, translate it into Spanish."))
    }

    @Test func parsesSpeechTranslationShapes() {
        let expected = SpeechTranslation(transcript: "Where is it?", translation: "¿Dónde está?")
        #expect(parseSpeechTranslation("Where is it?\nSpanish: ¿Dónde está?", target: spanish) == expected)
        #expect(parseSpeechTranslation("Where is it?\n\n\nspanish:  \"¿Dónde está?\" ", target: spanish) == expected)
        #expect(parseSpeechTranslation("Where is it?\nEspañol: ¿Dónde está?", target: spanish) == expected)
        #expect(parseSpeechTranslation("Where is it? Spanish: ¿Dónde está?", target: spanish) == expected)
        #expect(parseSpeechTranslation("Where is it?\n¿Dónde está?", target: spanish) == expected)
        #expect(parseSpeechTranslation("¿Dónde está?", target: spanish) == SpeechTranslation(transcript: "", translation: "¿Dónde está?"))
        #expect(parseSpeechTranslation("Ponlo ahí\nاردو: وہاں رکھو", target: urdu)
            == SpeechTranslation(transcript: "Ponlo ahí", translation: "وہاں رکھو"))
    }

    @Test func streamingParseKeepsFirstLineAsTranscript() {
        #expect(parseSpeechTranslation("Where is", target: spanish, isFinal: false)
            == SpeechTranslation(transcript: "Where is", translation: ""))
        #expect(parseSpeechTranslation("Where is it?\nSpanish: ¿Dón", target: spanish, isFinal: false)
            == SpeechTranslation(transcript: "Where is it?", translation: "¿Dón"))
    }

    @Test func parsesDetectedLanguage() {
        let candidates = Languages.all + [spanish]
        #expect(parseDetectedLanguage("ur", candidates: candidates) == urdu)
        #expect(parseDetectedLanguage(" UR.\n", candidates: candidates) == urdu)
        #expect(parseDetectedLanguage("`pa-Arab`", candidates: candidates) == punjabiPk)
        #expect(parseDetectedLanguage("**Urdu**", candidates: candidates) == urdu)
        #expect(parseDetectedLanguage("es", candidates: candidates) == spanish)
        #expect(parseDetectedLanguage("unknown", candidates: candidates) == nil)
        #expect(parseDetectedLanguage("I think", candidates: candidates) == nil)
        #expect(parseDetectedLanguage("", candidates: candidates) == nil)
    }

    @Test func cleanStripsLabelsAndWrappingQuotes() {
        #expect(cleanTranslation("  Translation: Hola \n", target: spanish) == "Hola")
        #expect(cleanTranslation("Spanish: \"Hola\"", target: spanish) == "Hola")
        #expect(cleanTranslation("«Hola»", target: spanish) == "Hola")
        let text = "Dijo \"hola\" y se fue.\nLuego: nada."
        #expect(cleanTranslation(text, target: spanish) == text)
    }

    @Test func detectsRunawayRepetition() {
        #expect(isRunaway("Urdu: براہ کرم " + String(repeating: "لاء ", count: 30)))
        #expect(isRunaway(String(repeating: "a", count: maxOutputChars + 1)))
        #expect(!isRunaway("Pon el cemento junto al muro y luego trae más ladrillos, por favor. Mañana empezamos a las ocho."))
        #expect(!isRunaway("short"))
    }

    @Test func guessesWhoTyped() {
        #expect(typedByCoworker(input: "سیمنٹ کہاں رکھوں؟", output: "¿Dónde pongo el cemento?"))
        #expect(!typedByCoworker(input: "¿Dónde está el cemento?", output: "سیمنٹ کہاں ہے؟"))
        #expect(!typedByCoworker(input: "ok", output: "ok"))
    }
}
