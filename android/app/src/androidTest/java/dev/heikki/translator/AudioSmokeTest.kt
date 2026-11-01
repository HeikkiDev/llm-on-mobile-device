package dev.heikki.translator

import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.ThinkingConfig
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Local-only smoke test of Gemma audio (SPEC-WALTER §9, never in CI): skipped unless the model and
 * WAV clips named `<code>.wav` (e.g. `es.wav`, `ur.wav`) are in the app's `files/smoke/` directory.
 * Results are logged under the tag "AudioSmoke".
 */
@RunWith(AndroidJUnit4::class)
class AudioSmokeTest {
    @Test
    fun translatesAndDetectsSpeechClips() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val model = File(context.filesDir, MODEL_FILE)
        val clips = File(context.filesDir, "smoke").listFiles { f -> f.extension == "wav" }.orEmpty().sorted()
        assumeTrue(model.length() == MODEL_SIZE_BYTES && clips.isNotEmpty())

        var t = SystemClock.elapsedRealtime()
        val engine = Engine(
            EngineConfig(
                modelPath = model.absolutePath,
                backend = Backend.CPU(),
                audioBackend = Backend.CPU(),
                maxNumTokens = 4096,
                cacheDir = context.cacheDir.path,
            )
        ).apply { initialize() }
        log("engine loaded in ${SystemClock.elapsedRealtime() - t} ms")

        fun ask(system: String, prompt: String, wav: ByteArray?): String = runBlocking {
            engine.createConversation(
                ConversationConfig(
                    systemInstruction = Contents.of(system),
                    samplerConfig = SamplerConfig(topK = 1, topP = 1.0, temperature = 0.0),
                    thinkingConfig = ThinkingConfig(enableThinking = false),
                )
            ).use { conv ->
                val out = StringBuilder()
                val contents = if (wav == null) Contents.of(prompt) else Contents.of(Content.Text(prompt), Content.AudioBytes(wav))
                try {
                    conv.sendMessageAsync(contents).collect {
                        out.append(it.toString())
                        if (out.length > 600) conv.cancelProcess()
                    }
                } catch (e: Exception) {
                    out.append(" [stopped: ${e.message}]")
                }
                out.toString()
            }
        }

        val urdu = Languages.byCode("ur")!!
        for (clip in clips) {
            val wav = clip.readBytes()
            val code = clip.nameWithoutExtension.trimEnd { it.isDigit() }
            val spoken = if (code == "es") Languages.spanish else Languages.byCode(code) ?: continue

            t = SystemClock.elapsedRealtime()
            val detected = ask(DETECTION_SYSTEM_INSTRUCTION, buildDetectionPrompt(Languages.all + Languages.spanish), wav)
            log("${clip.name} detect -> '${detected.trim()}' in ${SystemClock.elapsedRealtime() - t} ms")

            val target = if (spoken == Languages.spanish) urdu else Languages.spanish
            t = SystemClock.elapsedRealtime()
            val raw = ask(SPEECH_SYSTEM_INSTRUCTION, buildSpeechPrompt(spoken, target), wav)
            val parsed = parseSpeechTranslation(raw, target)
            log("${clip.name} ${spoken.code}->${target.code} in ${SystemClock.elapsedRealtime() - t} ms raw='$raw' parsed=$parsed")
            if (spoken != Languages.spanish) continue

            t = SystemClock.elapsedRealtime()
            val transcript = ask(SPEECH_SYSTEM_INSTRUCTION, buildTranscriptionPrompt(spoken), wav).trim()
            val asrMs = SystemClock.elapsedRealtime() - t
            t = SystemClock.elapsedRealtime()
            val text = ask(buildTextSystemInstruction(urdu), transcript, null)
            log("${clip.name} two-step asr ${asrMs} ms '$transcript' + text ${SystemClock.elapsedRealtime() - t} ms '$text'")
        }
        engine.close()
    }

    private fun log(msg: String) {
        Log.i("AudioSmoke", msg)
    }
}
