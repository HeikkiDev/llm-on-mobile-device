import Foundation
import Testing
@testable import Translator

struct PromptTests {
    private let spanish = Languages.byCode("es")!

    @Test func instructionNamesTargetAndAsksForTranslationOnly() {
        let instruction = buildSystemInstruction(spanish)
        #expect(instruction.contains("into Spanish (Español)"))
        #expect(instruction.contains("already in Spanish"))
        #expect(instruction.contains("Output ONLY the translation"))
    }

    @Test func cleanStripsLabelsAndWrappingQuotes() {
        #expect(cleanTranslation("  Translation: Hola \n", target: spanish) == "Hola")
        #expect(cleanTranslation("Spanish: \"Hola\"", target: spanish) == "Hola")
        #expect(cleanTranslation("«Hola»", target: spanish) == "Hola")
    }

    @Test func cleanLeavesNormalTextUntouched() {
        let text = "Dijo \"hola\" y se fue.\nLuego: nada."
        #expect(cleanTranslation(text, target: spanish) == text)
    }

    @Test func languageCodesAreUniqueAndDefaultAvoidsDeviceLanguage() {
        #expect(Set(Languages.all.map(\.code)).count == Languages.all.count)
        #expect(Languages.defaultLanguage(for: Locale(identifier: "en_US")).code == "es")
        #expect(Languages.defaultLanguage(for: Locale(identifier: "fr_FR")).code == "en")
    }

    @Test func searchIgnoresCaseAndAccentsAndMatchesNativeNames() {
        #expect(Languages.search(" espanol ").map(\.code) == ["es"])
        #expect(Languages.search("DEUTSCH").map(\.code) == ["de"])
        #expect(Languages.search("日本").map(\.code) == ["ja"])
        #expect(Languages.search("chinese").map(\.code) == ["zh"])
        #expect(Languages.search("") == Languages.all)
        #expect(Languages.search("xyz").isEmpty)
    }
}
