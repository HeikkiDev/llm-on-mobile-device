package dev.heikki.translator

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SpeakerTest {
    private data class Fake(val tag: String, val quality: Int = 300)

    private fun pick(code: String, vararg voices: Fake): Fake? =
        bestVoice(Languages.byCode(code)!!, voices.toList(), { Locale.forLanguageTag(it.tag) }, { it.quality })

    @Test
    fun arabicAcceptsAnyArabicVoice() {
        for (tag in listOf("ar", "ar-XA", "ar-001")) {
            assertEquals(tag, pick("ar", Fake("es-ES"), Fake(tag))?.tag)
            assertEquals(tag, pick("ary", Fake("es-ES"), Fake(tag))?.tag)
        }
        assertNull(pick("ar", Fake("es-ES"), Fake("fa-IR")))
    }

    @Test
    fun arabicPrefersExactRegionThenQuality() {
        assertEquals("ar-SA", pick("ar", Fake("ar-XA", 500), Fake("ar-SA", 300))?.tag)
        assertEquals("ar-MA", pick("ary", Fake("ar-SA", 500), Fake("ar-MA", 300))?.tag)
        assertEquals("ar-XA", pick("ar", Fake("ar", 300), Fake("ar-XA", 400), Fake("ar-001", 200))?.tag)
    }

    @Test
    fun punjabiVariantsDoNotMix() {
        assertNull(pick("pa", Fake("pa-PK")))
        assertNull(pick("pa-Arab", Fake("pa-IN")))
        assertEquals("pa-IN", pick("pa", Fake("pa-PK"), Fake("pa-IN"))?.tag)
        assertEquals("pa-PK", pick("pa-Arab", Fake("pa-PK"), Fake("pa-IN"))?.tag)
    }

    private fun pickWithFallback(code: String, vararg voices: Fake): Fake? =
        withFallback(Languages.byCode(code)!!) { pickFor(it, *voices) }

    private fun pickFor(language: Language, vararg voices: Fake): Fake? =
        bestVoice(language, voices.toList(), { Locale.forLanguageTag(it.tag) }, { it.quality })

    @Test
    fun punjabiPakistanFallsBackToUrduVoice() {
        assertEquals("ur-PK", pickWithFallback("pa-Arab", Fake("pa-IN"), Fake("ur-PK"))?.tag)
        assertEquals("pa-PK", pickWithFallback("pa-Arab", Fake("pa-PK"), Fake("ur-PK"))?.tag) // own voice wins
        assertNull(pickWithFallback("pa-Arab", Fake("pa-IN"))) // never the Gurmukhi voice
        assertNull(pickWithFallback("pa", Fake("ur-PK"))) // Indian Punjabi has no fallback
        assertNull(pickWithFallback("ps", Fake("ur-PK")))
    }
}
