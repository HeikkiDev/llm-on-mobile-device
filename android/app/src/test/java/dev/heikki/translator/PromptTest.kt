package dev.heikki.translator

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PromptTest {
    private val spanish = Languages.spanish
    private val urdu = Languages.byCode("ur")!!
    private val punjabiPk = Languages.byCode("pa-Arab")!!

    @Test
    fun speechPromptUsesAstTemplateWithEnglishNames() {
        val prompt = buildSpeechPrompt(spanish, urdu)
        assertTrue(prompt.startsWith("Transcribe the following speech segment in Spanish, then translate it into Urdu."))
        assertTrue(prompt.contains("then output the string 'Urdu: ', then the translation in Urdu."))
    }

    @Test
    fun speechPromptAddsScriptHint() {
        assertTrue(buildSpeechPrompt(punjabiPk, spanish).contains("Shahmukhi"))
        assertTrue(buildTextSystemInstruction(punjabiPk).contains("Shahmukhi"))
    }

    @Test
    fun detectionPromptListsCodes() {
        val prompt = buildDetectionPrompt(listOf(urdu, spanish))
        assertTrue(prompt.contains("nothing else: ur, es."))
        assertTrue(prompt.contains("answer: unknown"))
    }

    @Test
    fun textInstructionIsBidirectional() {
        val instruction = buildTextSystemInstruction(urdu)
        assertTrue(instruction.contains("If the user's text is in Spanish, translate it into Urdu."))
        assertTrue(instruction.contains("Otherwise, translate it into Spanish."))
    }

    @Test
    fun parsesSpeechTranslationShapes() {
        val expected = SpeechTranslation("Where is it?", "¿Dónde está?")
        assertEquals(expected, parseSpeechTranslation("Where is it?\nSpanish: ¿Dónde está?", spanish))
        assertEquals(expected, parseSpeechTranslation("Where is it?\n\n\nspanish:  \"¿Dónde está?\" ", spanish))
        assertEquals(expected, parseSpeechTranslation("Where is it?\nEspañol: ¿Dónde está?", spanish))
        assertEquals(expected, parseSpeechTranslation("Where is it? Spanish: ¿Dónde está?", spanish))
        assertEquals(expected, parseSpeechTranslation("Where is it?\n¿Dónde está?", spanish))
        assertEquals(SpeechTranslation("", "¿Dónde está?"), parseSpeechTranslation("¿Dónde está?", spanish))
        assertEquals(SpeechTranslation("Ponlo ahí", "وہاں رکھو"), parseSpeechTranslation("Ponlo ahí\nاردو: وہاں رکھو", urdu))
    }

    @Test
    fun streamingParseKeepsFirstLineAsTranscript() {
        assertEquals(SpeechTranslation("Where is", ""), parseSpeechTranslation("Where is", spanish, final = false))
        assertEquals(SpeechTranslation("Where is it?", "¿Dón"), parseSpeechTranslation("Where is it?\nSpanish: ¿Dón", spanish, final = false))
    }

    @Test
    fun parsesDetectedLanguage() {
        val candidates = Languages.all + spanish
        assertEquals(urdu, parseDetectedLanguage("ur", candidates))
        assertEquals(urdu, parseDetectedLanguage(" UR.\n", candidates))
        assertEquals(punjabiPk, parseDetectedLanguage("`pa-Arab`", candidates))
        assertEquals(urdu, parseDetectedLanguage("**Urdu**", candidates))
        assertEquals(spanish, parseDetectedLanguage("es", candidates))
        assertNull(parseDetectedLanguage("unknown", candidates))
        assertNull(parseDetectedLanguage("I think", candidates))
        assertNull(parseDetectedLanguage("", candidates))
    }

    @Test
    fun cleanStripsLabelsAndWrappingQuotes() {
        assertEquals("Hola", cleanTranslation("  Translation: Hola \n", spanish))
        assertEquals("Hola", cleanTranslation("Spanish: \"Hola\"", spanish))
        assertEquals("Hola", cleanTranslation("«Hola»", spanish))
        val text = "Dijo \"hola\" y se fue.\nLuego: nada."
        assertEquals(text, cleanTranslation(text, spanish))
    }

    @Test
    fun detectsRunawayRepetition() {
        assertTrue(isRunaway("Urdu: براہ کرم " + "لاء ".repeat(30)))
        assertTrue(isRunaway("a".repeat(MAX_OUTPUT_CHARS + 1)))
        assertFalse(isRunaway("Pon el cemento junto al muro y luego trae más ladrillos, por favor. Mañana empezamos a las ocho."))
        assertFalse(isRunaway("short"))
    }

    @Test
    fun guessesWhoTyped() {
        assertTrue(typedByCoworker("سیمنٹ کہاں رکھوں؟", "¿Dónde pongo el cemento?"))
        assertFalse(typedByCoworker("¿Dónde está el cemento?", "سیمنٹ کہاں ہے؟"))
        assertFalse(typedByCoworker("ok", "ok"))
    }
}
