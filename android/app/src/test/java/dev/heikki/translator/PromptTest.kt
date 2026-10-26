package dev.heikki.translator

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class PromptTest {
    private val spanish = Languages.byCode("es")!!

    @Test
    fun instructionNamesTargetAndAsksForTranslationOnly() {
        val instruction = buildSystemInstruction(spanish)
        assertTrue(instruction.contains("into Spanish (Español)"))
        assertTrue(instruction.contains("already in Spanish"))
        assertTrue(instruction.contains("Output ONLY the translation"))
    }

    @Test
    fun cleanStripsLabelsAndWrappingQuotes() {
        assertEquals("Hola", cleanTranslation("  Translation: Hola \n", spanish))
        assertEquals("Hola", cleanTranslation("Spanish: \"Hola\"", spanish))
        assertEquals("Hola", cleanTranslation("«Hola»", spanish))
    }

    @Test
    fun cleanLeavesNormalTextUntouched() {
        val text = "Dijo \"hola\" y se fue.\nLuego: nada."
        assertEquals(text, cleanTranslation(text, spanish))
    }

    @Test
    fun languageCodesAreUniqueAndDefaultAvoidsDeviceLanguage() {
        assertEquals(Languages.all.size, Languages.all.map { it.code }.toSet().size)
        assertEquals("es", Languages.default(Locale.US).code)
        assertEquals("en", Languages.default(Locale.FRANCE).code)
    }

    @Test
    fun searchIgnoresCaseAndAccentsAndMatchesNativeNames() {
        assertEquals(listOf("es"), Languages.search(" espanol ").map { it.code })
        assertEquals(listOf("de"), Languages.search("DEUTSCH").map { it.code })
        assertEquals(listOf("ja"), Languages.search("日本").map { it.code })
        assertEquals(listOf("zh"), Languages.search("chinese").map { it.code })
        assertEquals(Languages.all, Languages.search(""))
        assertTrue(Languages.search("xyz").isEmpty())
    }
}
