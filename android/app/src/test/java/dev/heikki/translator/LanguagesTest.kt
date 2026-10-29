package dev.heikki.translator

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LanguagesTest {
    @Test
    fun entriesAreCompleteAndUnique() {
        assertEquals(Languages.all.size, Languages.all.map { it.code }.toSet().size)
        assertFalse(Languages.all.any { it.code == "es" })
        Languages.all.forEach {
            assertTrue(it.code, it.spanishName.isNotBlank() && it.englishName.isNotBlank())
            assertTrue(it.code, it.nativeName.isNotBlank() && it.speakWord.isNotBlank())
        }
        assertEquals("ur", Languages.all.first().code)
    }

    @Test
    fun searchMatchesSpanishEnglishNativeAndCode() {
        assertEquals(listOf("ps"), Languages.search(" pastun ").map { it.code })
        assertEquals(listOf("ps"), Languages.search("PASHTO").map { it.code })
        assertEquals(listOf("ur"), Languages.search("اردو").map { it.code })
        assertEquals(listOf("pa-Arab"), Languages.search("pa-arab").map { it.code })
        assertEquals(Languages.alphabetical, Languages.search(""))
        assertTrue(Languages.search("xyz").isEmpty())
    }

    @Test
    fun recentsAreDedupedCappedAndMostRecentFirst() {
        var recents = emptyList<String>()
        for (code in listOf("ur", "hi", "ar", "ro", "en", "fr", "hi")) recents = Languages.pushRecent(recents, code)
        assertEquals(listOf("hi", "fr", "en", "ro", "ar"), recents)
        assertEquals(recents, Languages.decodeRecents(Languages.encodeRecents(recents)))
        assertEquals(listOf("ur"), Languages.decodeRecents("ur,bogus,ur,"))
        assertEquals(emptyList<String>(), Languages.decodeRecents(null))
    }

    @Test
    fun relatedPutsLanguageFirst() {
        assertEquals(listOf("hi", "ur", "pa-Arab", "pa", "sd"), Languages.related(Languages.byCode("hi")!!).map { it.code })
        assertEquals(listOf("en"), Languages.related(Languages.byCode("en")!!).map { it.code })
    }
}
