import Foundation
import Testing
@testable import Translator

struct LanguagesTests {
    @Test func entriesAreCompleteAndUnique() {
        #expect(Set(Languages.all.map(\.code)).count == Languages.all.count)
        #expect(!Languages.all.contains { $0.code == "es" })
        for language in Languages.all {
            #expect(!language.spanishName.isEmpty && !language.englishName.isEmpty, "\(language.code)")
            #expect(!language.nativeName.isEmpty && !language.speakWord.isEmpty, "\(language.code)")
        }
        #expect(Languages.all.first?.code == "ur")
    }

    @Test func searchMatchesSpanishEnglishNativeAndCode() {
        #expect(Languages.search(" pastun ").map(\.code) == ["ps"])
        #expect(Languages.search("PASHTO").map(\.code) == ["ps"])
        #expect(Languages.search("اردو").map(\.code) == ["ur"])
        #expect(Languages.search("pa-arab").map(\.code) == ["pa-Arab"])
        #expect(Languages.search("") == Languages.alphabetical)
        #expect(Languages.search("xyz").isEmpty)
    }

    @Test func recentsAreDedupedCappedAndMostRecentFirst() {
        var recents: [String] = []
        for code in ["ur", "hi", "ar", "ro", "en", "fr", "hi"] { recents = Languages.pushRecent(recents, code) }
        #expect(recents == ["hi", "fr", "en", "ro", "ar"])
        #expect(Languages.decodeRecents(Languages.encodeRecents(recents)) == recents)
        #expect(Languages.decodeRecents("ur,bogus,ur,") == ["ur"])
        #expect(Languages.decodeRecents(nil).isEmpty)
    }

    @Test func relatedPutsLanguageFirst() {
        #expect(Languages.related(Languages.byCode("hi")!).map(\.code) == ["hi", "ur", "pa-Arab", "pa", "sd"])
        #expect(Languages.related(Languages.byCode("en")!).map(\.code) == ["en"])
    }

    @Test func voiceFitPrefersSameRegion() {
        let urdu = Languages.byCode("ur")!
        #expect(urdu.voiceFit("ur-PK") == 1)
        #expect(urdu.voiceFit("ur-IN") == 0)
        #expect(urdu.voiceFit("hi-IN") == nil)
        #expect(Languages.spanish.voiceFit("es-ES") == 1)
        #expect(Languages.spanish.voiceFit("es-MX") == 0)
        // Both Punjabi entries share "pa": only the matching region (script) counts.
        #expect(Languages.byCode("pa")!.voiceFit("pa-IN") == 1)
        #expect(Languages.byCode("pa")!.voiceFit("pa-PK") == nil)
        #expect(Languages.byCode("pa-Arab")!.voiceFit("pa-PK") == 1)
    }
}
