package dev.heikki.translator

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import java.util.Locale

/**
 * Thin wrapper over the system TextToSpeech that only ever uses voices installed on the phone
 * (SPEC-WALTER §4.2): no network voices, and network synthesis is explicitly disabled.
 *
 * Binds to Google's engine when it is installed, whatever the phone's default engine is: Google's has the
 * Urdu (Pakistan) voice as a free download (verified 2026-11-20); maker engines (Samsung, Xiaomi…) may not.
 */
class Speaker(context: Context, private val onReady: () -> Unit) {
    /** Package of the engine in use (null = the phone's default), so "Instalar voz" opens the right installer. */
    val engine: String? = preferredEngine(context)
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var ready = false
    private var onDone: (() -> Unit)? = null
    private var voices: List<Voice> = emptyList()
    private var downloadable: List<Voice> = emptyList() // offline voices the engine lists but hasn't downloaded
    private var current = 0 // id of the utterance [onDone] belongs to; main thread only
    private val tts: TextToSpeech = TextToSpeech(context.applicationContext, { status ->
        ready = status == TextToSpeech.SUCCESS
        main.post(onReady)
    }, engine)

    init {
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit
            override fun onDone(utteranceId: String?) = finish(utteranceId)
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) = finish(utteranceId)
            override fun onError(utteranceId: String?, errorCode: Int) = finish(utteranceId)
            override fun onStop(utteranceId: String?, interrupted: Boolean) = finish(utteranceId)
        })
    }

    private fun finish(utteranceId: String?) {
        main.post { deliver(utteranceId) }
    }

    private fun deliver(utteranceId: String?) {
        if (utteranceId != current.toString()) return
        val callback = onDone
        onDone = null
        callback?.invoke()
    }

    /** Re-reads the installed voices (the user may have added one in system settings). */
    fun reloadVoices() {
        val local = if (ready) runCatching { tts.voices }.getOrNull().orEmpty().filter { !it.isNetworkConnectionRequired } else emptyList()
        val (missing, installed) = local.partition { TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED in it.features }
        voices = installed
        downloadable = missing
    }

    /** True when the engine offers an offline voice for [language] that is not downloaded yet (e.g. Urdu). */
    fun canInstallVoice(language: Language): Boolean =
        withFallback(language) { bestVoice(it, downloadable, { v -> v.locale }, { v -> v.quality }) } != null

    /** An installed voice for [language] (or its [Language.fallbackVoice]), or null. Never a network voice. */
    fun offlineVoice(language: Language): Voice? = withFallback(language) { bestVoice(it, voices, { v -> v.locale }, { v -> v.quality }) }

    fun hasOfflineVoice(language: Language): Boolean = offlineVoice(language) != null

    /** Speaks [text]; [onDone] runs on the main thread when it finishes or is stopped. False if no voice. */
    fun speak(text: String, language: Language, rate: Float, onDone: () -> Unit): Boolean {
        val voice = offlineVoice(language) ?: return false
        stop()
        current++
        this.onDone = onDone
        tts.voice = voice
        tts.setSpeechRate(rate)
        val params = Bundle().apply {
            putString(TextToSpeech.Engine.KEY_FEATURE_NETWORK_SYNTHESIS, "false")
        }
        if (tts.speak(text, TextToSpeech.QUEUE_FLUSH, params, current.toString()) != TextToSpeech.SUCCESS) {
            this.onDone = null
            return false
        }
        return true
    }

    fun stop() {
        onDone = null
        if (ready) tts.stop()
    }

    fun shutdown() {
        onDone = null
        tts.shutdown()
    }
}

/** [pick] for [language], else for its fallback voice language (e.g. Urdu for Punjabi-Pakistan). */
internal fun <T> withFallback(language: Language, pick: (Language) -> T?): T? =
    pick(language) ?: language.fallbackVoice?.let(Languages::byCode)?.let(pick)

const val GOOGLE_TTS = "com.google.android.tts"

/** Google's TTS engine if it is installed and enabled, else null (the phone's default engine). */
internal fun preferredEngine(context: Context): String? {
    val google = Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE).setPackage(GOOGLE_TTS)
    return GOOGLE_TTS.takeIf { context.packageManager.queryIntentServices(google, 0).isNotEmpty() }
}

/**
 * The best of [voices] for [language]: same voice language, preferring the exact region, then higher quality.
 * Punjabi India/Pakistan are different languages in practice, so only the exact region counts there.
 * Everything else (Arabic voices are ar, ar-XA, ar-001) accepts any region.
 */
internal fun <T> bestVoice(language: Language, voices: List<T>, localeOf: (T) -> Locale, qualityOf: (T) -> Int): T? {
    val wanted = Locale.forLanguageTag(language.voiceLocale ?: return null)
    val sameLanguage = voices.filter { localeOf(it).language == wanted.language }
    val sameCountry = sameLanguage.filter { localeOf(it).country == wanted.country }
    return (if (wanted.language == "pa") sameCountry else sameCountry.ifEmpty { sameLanguage })
        .maxByOrNull(qualityOf)
}
