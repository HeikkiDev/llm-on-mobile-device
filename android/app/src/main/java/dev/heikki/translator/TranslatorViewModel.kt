package dev.heikki.translator

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.media.AudioManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.ThinkingConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors

private val BACKEND: Backend = Backend.CPU()
private const val MAX_NUM_TOKENS = 4096
private const val LOW_RAM_BYTES = 7_500_000_000L
const val MAX_INPUT_CHARS = 1000
const val WARN_RECORDING_MS = 25_000
private const val COWORKER_SPEECH_RATE = 0.9f
private const val LOW_VOLUME = 0.4f
private const val PREFS = "settings"
private const val PREF_COWORKER = "coworker"
private const val PREF_RECENTS = "recents"

const val NOT_UNDERSTOOD = "No he entendido, repite más despacio"
const val NOTHING_HEARD = "No he oído nada"
const val SPANISH_DETECTED = "Eso parece español. Pídele a tu compañero que diga una frase."

sealed interface ModelStatus {
    data class Missing(val partialBytes: Long) : ModelStatus
    data object NeedsCellularConsent : ModelStatus
    data class Downloading(val bytes: Long, val total: Long) : ModelStatus
    data object Verifying : ModelStatus
    data object Loading : ModelStatus
    data object Ready : ModelStatus
    /** [loadFailed]: the file is present but the engine could not load it. */
    data class Error(val message: String, val loadFailed: Boolean = false) : ModelStatus
}

enum class Side { Walter, Coworker }

fun Side.other() = if (this == Side.Walter) Side.Coworker else Side.Walter

/** One bubble. [transcript] is what was said (or typed); [translation] is shown large and spoken. */
data class Turn(
    val id: Long,
    val side: Side,
    val source: Language,
    val target: Language,
    val transcript: String = "",
    val translation: String = "",
    val done: Boolean = false,
    val error: String? = null,
    val typed: Boolean = false,
)

/** What the app is doing right now; only one turn at a time (SPEC-WALTER §5.2). */
sealed interface Activity {
    data object Idle : Activity
    data class Listening(val side: Side, val level: Float = 0f, val elapsedMs: Int = 0, val detecting: Boolean = false) : Activity
    data class Translating(val side: Side) : Activity
    data object Detecting : Activity
    data class Speaking(val turnId: Long) : Activity
}

enum class DetectSheet { Ask, Failed }

data class UiState(
    val modelStatus: ModelStatus = ModelStatus.Missing(0),
    val lowRam: Boolean = false,
    val coworker: Language? = null,
    val recents: List<Language> = emptyList(),
    val turns: List<Turn> = emptyList(),
    val activity: Activity = Activity.Idle,
    /** The side whose button pulses: it is their turn to speak. */
    val hint: Side? = null,
    val detectSheet: DetectSheet? = null,
    /** Just detected; the banner asks "¿Correcto?". */
    val confirmLanguage: Language? = null,
    /** Codes of languages (including "es") without an offline voice on this phone. */
    val voiceless: Set<String> = emptySet(),
    val lowVolume: Boolean = false,
    /** Short notice shown once in a snackbar. */
    val message: String? = null,
)

private sealed interface Request {
    class Audio(val side: Side, val wav: ByteArray) : Request
    class Text(val text: String) : Request
}

private class Runaway : Exception()

class TranslatorViewModel(app: Application) : AndroidViewModel(app) {
    private val downloader = ModelDownloader(app.filesDir)

    // Single owner of the Engine (litert-lm skill): every engine call runs on this one thread.
    private val executor = Executors.newSingleThreadExecutor()
    private val engineDispatcher = executor.asCoroutineDispatcher()
    private val engineScope = CoroutineScope(SupervisorJob() + engineDispatcher)
    private val mutex = Mutex()
    private var engine: Engine? = null
    private var conversation: Conversation? = null
    private var streaming = false // touched only on the executor thread
    @Volatile private var stopRequested = false
    private var downloadJob: Job? = null
    private var turnJob: Job? = null
    private var cellularAccepted = false

    private val recorder = AudioRecorder()
    private val speaker = Speaker(app) { refreshDevice() }
    private var nextTurnId = 1L
    /** Kept in memory only, for "Reintentar"; replaced by the next turn (§5.2). */
    private var lastRequest: Request? = null

    private val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(
        UiState(
            lowRam = isLowRam(app),
            coworker = Languages.byCode(prefs.getString(PREF_COWORKER, null)),
            recents = Languages.decodeRecents(prefs.getString(PREF_RECENTS, null)).mapNotNull { Languages.byCode(it) },
        )
    )
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        if (downloader.isModelValid()) load() else showMissing()
    }

    fun download() {
        if (downloadJob?.isActive == true) return
        when (networkKind(getApplication())) {
            NetworkKind.None -> return setStatus(ModelStatus.Error(NO_CONNECTION))
            NetworkKind.Metered -> if (!cellularAccepted) return setStatus(ModelStatus.NeedsCellularConsent)
            NetworkKind.Unmetered -> Unit
        }
        setStatus(ModelStatus.Downloading(downloader.partialBytes(), MODEL_SIZE_BYTES))
        downloadJob = viewModelScope.launch {
            try {
                downloader.download(
                    onProgress = { setStatus(ModelStatus.Downloading(it, MODEL_SIZE_BYTES)) },
                    onVerifying = { setStatus(ModelStatus.Verifying) },
                )
                load()
            } catch (e: CancellationException) {
                showMissing()
                throw e
            } catch (e: Exception) {
                setStatus(ModelStatus.Error(e.message ?: "La descarga ha fallado. Vuelve a intentarlo."))
            }
        }
    }

    fun acceptCellular() {
        cellularAccepted = true
        download()
    }

    fun waitForWifi() = showMissing()

    fun cancelDownload() {
        downloadJob?.cancel()
    }

    fun deleteModelAndDownloadAgain() {
        engineScope.launch {
            mutex.withLock {
                release()
                downloader.deleteModel()
            }
            showMissing()
        }
    }

    private fun load() {
        setStatus(ModelStatus.Loading)
        engineScope.launch {
            mutex.withLock {
                try {
                    loadEngine()
                } catch (e: Exception) {
                    setStatus(ModelStatus.Error(e.message ?: "No se ha podido cargar el modelo.", loadFailed = true))
                    return@withLock
                }
                setStatus(ModelStatus.Ready)
            }
        }
    }

    // Call only on engineScope while holding the mutex.
    private fun loadEngine(): Engine {
        engine?.let { return it }
        val config = EngineConfig(
            modelPath = downloader.modelFile.absolutePath,
            backend = BACKEND,
            audioBackend = Backend.CPU(),
            maxNumTokens = MAX_NUM_TOKENS,
            cacheDir = getApplication<Application>().cacheDir.path,
        )
        // Store the engine only after initialize() returns (never close a failed one).
        return Engine(config).also {
            it.initialize()
            engine = it
        }
    }

    // ---- Language ----

    fun setCoworker(language: Language) {
        val recents = Languages.pushRecent(_state.value.recents.map { it.code }, language.code)
        prefs.edit()
            .putString(PREF_COWORKER, language.code)
            .putString(PREF_RECENTS, Languages.encodeRecents(recents))
            .apply()
        _state.update { s -> s.copy(coworker = language, recents = recents.mapNotNull { Languages.byCode(it) }) }
    }

    fun openDetection() {
        if (_state.value.activity !is Activity.Idle) stopSpeaking()
        _state.update { it.copy(detectSheet = DetectSheet.Ask, confirmLanguage = null) }
    }

    fun closeDetection() {
        val activity = _state.value.activity
        if (activity is Activity.Listening && activity.detecting) turnJob?.cancel()
        _state.update { it.copy(detectSheet = null) }
    }

    fun confirmDetected() = _state.update { it.copy(confirmLanguage = null) }

    // ---- Turns ----

    /** A tap on "Hablo yo" / "Habla él" (or the detection mic): start, stop, or barge in (§4.1, §5.2). */
    fun onMic(side: Side, detecting: Boolean = false) {
        when (val activity = _state.value.activity) {
            is Activity.Listening -> if (activity.side == side) recorder.requestStop()
            is Activity.Translating -> if (activity.side == side) stop()
            is Activity.Speaking -> {
                // Barge-in: the other side answers right away; the same side's button just stops the voice.
                val speakingSide = _state.value.turns.firstOrNull { it.id == activity.turnId }?.side
                stopSpeaking()
                if (speakingSide != side || detecting) listen(side, detecting)
            }
            Activity.Idle -> listen(side, detecting)
            Activity.Detecting -> Unit
        }
    }

    private fun listen(side: Side, detecting: Boolean) {
        val s = _state.value
        if (s.modelStatus != ModelStatus.Ready || (s.coworker == null && !detecting)) return
        refreshDevice()
        _state.update { it.copy(activity = Activity.Listening(side, detecting = detecting), hint = null, message = null) }
        turnJob = viewModelScope.launch {
            val recording = try {
                recorder.record { level, elapsedMs ->
                    _state.update {
                        val listening = it.activity as? Activity.Listening ?: return@update it
                        it.copy(activity = listening.copy(level = level, elapsedMs = elapsedMs))
                    }
                }
            } catch (e: CancellationException) {
                setActivity(Activity.Idle)
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(activity = Activity.Idle, message = e.message ?: "No se puede usar el micrófono.") }
                return@launch
            }
            when (recording) {
                Recording.NoSpeech -> _state.update { it.copy(activity = Activity.Idle, message = NOTHING_HEARD) }
                is Recording.Speech -> if (detecting) detect(recording.wav) else translateAudio(side, recording.wav)
            }
        }
    }

    private suspend fun detect(wav: ByteArray) {
        setActivity(Activity.Detecting)
        val raw = try {
            infer(DETECTION_SYSTEM_INSTRUCTION, Contents.of(Content.Text(buildDetectionPrompt(DETECTION_CANDIDATES)), Content.AudioBytes(wav)))
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            ""
        }
        when (val language = parseDetectedLanguage(raw, DETECTION_CANDIDATES)) {
            null -> _state.update { it.copy(activity = Activity.Idle, detectSheet = DetectSheet.Failed) }
            Languages.spanish -> _state.update { it.copy(activity = Activity.Idle, message = SPANISH_DETECTED) }
            else -> {
                setCoworker(language)
                _state.update { it.copy(detectSheet = null, confirmLanguage = language) }
                // The same clip becomes the first bubble, so detection costs no extra turn (§5.3).
                translateAudio(Side.Coworker, wav)
            }
        }
    }

    private suspend fun translateAudio(side: Side, wav: ByteArray) {
        val coworker = _state.value.coworker ?: return setActivity(Activity.Idle)
        val (source, target) = if (side == Side.Walter) Languages.spanish to coworker else coworker to Languages.spanish
        lastRequest = Request.Audio(side, wav)
        val id = addTurn(Turn(nextTurnId++, side, source, target), Activity.Translating(side))
        val contents = Contents.of(Content.Text(buildSpeechPrompt(source, target)), Content.AudioBytes(wav))
        runTurn(id, SPEECH_SYSTEM_INSTRUCTION, contents) { raw, final ->
            val parsed = parseSpeechTranslation(raw, target, final)
            parsed.transcript to parsed.translation
        }
    }

    /** Writing mode (§3.3 C): one prompt for both directions; the bubble side is guessed afterwards. */
    fun translateText(input: String) {
        val text = input.trim()
        val s = _state.value
        val coworker = s.coworker ?: return
        if (s.modelStatus != ModelStatus.Ready || text.isEmpty() || text.length > MAX_INPUT_CHARS) return
        if (s.activity !is Activity.Idle) stopSpeaking()
        if (_state.value.activity !is Activity.Idle) return
        lastRequest = Request.Text(text)
        val id = addTurn(
            Turn(nextTurnId++, Side.Walter, Languages.spanish, coworker, transcript = text, typed = true),
            Activity.Translating(Side.Walter),
        )
        turnJob = viewModelScope.launch {
            runTurn(id, buildTextSystemInstruction(coworker), Contents.of(text)) { raw, _ -> text to raw.trim() }
            val turn = _state.value.turns.firstOrNull { it.id == id } ?: return@launch
            if (turn.done && typedByCoworker(text, turn.translation)) {
                // Typed in the coworker's language: it belongs on his side and is spoken in Spanish.
                updateTurn(id) { it.copy(side = Side.Coworker, source = coworker, target = Languages.spanish) }
            }
            _state.value.turns.firstOrNull { it.id == id }?.let { if (it.done) speak(it) }
        }
    }

    fun retry(turnId: Long) {
        if (_state.value.activity !is Activity.Idle) return
        val request = lastRequest ?: return
        _state.update { s -> s.copy(turns = s.turns.filterNot { it.id == turnId }) }
        when (request) {
            is Request.Audio -> turnJob = viewModelScope.launch { translateAudio(request.side, request.wav) }
            is Request.Text -> translateText(request.text)
        }
    }

    /** Streams one inference into turn [id]; [split] maps the raw output to (transcript, translation). */
    private suspend fun runTurn(
        id: Long,
        system: String,
        contents: Contents,
        split: (raw: String, final: Boolean) -> Pair<String, String>,
    ) {
        var raw = ""
        try {
            raw = infer(system, contents) { partial ->
                val (transcript, translation) = split(partial, false)
                updateTurn(id) { it.copy(transcript = transcript, translation = translation) }
            }
        } catch (e: CancellationException) {
            updateTurn(id) { it.copy(done = true, error = NOT_UNDERSTOOD) }
            setActivity(Activity.Idle)
            throw e
        } catch (e: Runaway) {
            updateTurn(id) { it.copy(done = true, translation = "", error = NOT_UNDERSTOOD) }
            return setActivity(Activity.Idle)
        } catch (e: Exception) {
            updateTurn(id) { it.copy(done = true, error = e.message ?: "No se ha podido traducir.") }
            return setActivity(Activity.Idle)
        }
        if (stopRequested) {
            stopRequested = false
            // Stopped by the user: keep what was produced, don't speak it.
            _state.update { s ->
                s.copy(
                    turns = s.turns.mapNotNull { t ->
                        if (t.id != id) t else if (t.translation.isBlank()) null else t.copy(done = true)
                    },
                    activity = Activity.Idle,
                )
            }
            return
        }
        val (transcript, translation) = split(raw, true)
        if (translation.isBlank()) {
            updateTurn(id) { it.copy(done = true, transcript = transcript, error = NOT_UNDERSTOOD) }
            return setActivity(Activity.Idle)
        }
        updateTurn(id) { it.copy(done = true, transcript = transcript, translation = translation) }
        val turn = _state.value.turns.first { it.id == id }
        if (!turn.typed) speak(turn)
    }

    /** One single-shot inference on the engine thread; [onText] gets the accumulated output. */
    private suspend fun infer(system: String, contents: Contents, onText: (String) -> Unit = {}): String =
        withContext(engineDispatcher) {
            stopRequested = false
            // Single-shot: a fresh conversation per turn keeps history out of the prompt.
            val conv = mutex.withLock {
                conversation?.close()
                conversation = null
                loadEngine().createConversation(
                    ConversationConfig(
                        systemInstruction = Contents.of(system),
                        samplerConfig = SamplerConfig(topK = 1, topP = 1.0, temperature = 0.0),
                        thinkingConfig = ThinkingConfig(enableThinking = false),
                    )
                ).also {
                    conversation = it
                    streaming = true
                }
            }
            val out = StringBuilder()
            var runaway = false
            try {
                conv.sendMessageAsync(contents).collect { chunk ->
                    if (runaway) return@collect
                    out.append(chunk.toString())
                    if (isRunaway(out)) {
                        runaway = true
                        conv.cancelProcess()
                    } else {
                        onText(out.toString())
                    }
                }
            } catch (e: Exception) {
                // cancelProcess() ends the stream with an exception; that is expected after a stop.
                if (!runaway && !stopRequested) throw e
            } finally {
                streaming = false
            }
            ensureActive()
            if (runaway) throw Runaway()
            out.toString()
        }

    fun stop() {
        if (_state.value.activity !is Activity.Translating) return
        stopRequested = true
        engineScope.launch { conversation?.cancelProcess() }
    }

    // ---- Speaking ----

    fun repeat(turnId: Long) {
        val activity = _state.value.activity
        if (activity is Activity.Listening || activity is Activity.Translating || activity is Activity.Detecting) return
        _state.value.turns.firstOrNull { it.id == turnId && it.done && it.error == null }?.let { speak(it) }
    }

    private fun speak(turn: Turn) {
        refreshDevice()
        val rate = if (turn.target == Languages.spanish) 1.0f else COWORKER_SPEECH_RATE
        val started = speaker.speak(turn.translation, turn.target, rate) {
            _state.update {
                if (it.activity == Activity.Speaking(turn.id)) it.copy(activity = Activity.Idle, hint = turn.side.other()) else it
            }
        }
        _state.update {
            if (started) it.copy(activity = Activity.Speaking(turn.id), hint = null)
            else it.copy(activity = Activity.Idle, hint = turn.side.other()) // no voice: the screen is shown instead
        }
    }

    fun stopSpeaking() {
        speaker.stop()
        _state.update { if (it.activity is Activity.Speaking) it.copy(activity = Activity.Idle) else it }
    }

    // ---- Misc ----

    /** "Nueva": forget the conversation (it only ever lived in memory). */
    fun newConversation() {
        if (_state.value.activity is Activity.Translating || _state.value.activity is Activity.Detecting) return
        stopSpeaking()
        lastRequest = null
        _state.update { it.copy(turns = emptyList(), hint = null, confirmLanguage = null) }
    }

    fun messageShown() = _state.update { it.copy(message = null) }

    fun dismissLowVolume() = _state.update { it.copy(lowVolume = false) }

    /** On start, on resume and before each turn: installed voices and volume may have changed. */
    fun refreshDevice() {
        speaker.reloadVoices()
        val voiceless = (Languages.all + Languages.spanish).filterNot { speaker.hasOfflineVoice(it) }.map { it.code }.toSet()
        val audio = getApplication<Application>().getSystemService(AudioManager::class.java)
        val volume = audio.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() /
            audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        _state.update { it.copy(voiceless = voiceless, lowVolume = volume < LOW_VOLUME) }
    }

    /** Frees ~2 GB under memory pressure; the next turn reloads the engine lazily. */
    fun releaseIfIdle() {
        if (_state.value.activity is Activity.Translating || _state.value.activity is Activity.Detecting) return
        engineScope.launch { mutex.withLock { release() } }
    }

    // Call only on engineScope while holding the mutex.
    private suspend fun release() {
        while (streaming) {
            conversation?.cancelProcess()
            delay(100)
        }
        conversation?.close()
        conversation = null
        engine?.close()
        engine = null
    }

    private fun addTurn(turn: Turn, activity: Activity): Long {
        _state.update { it.copy(turns = it.turns + turn, activity = activity, hint = null) }
        return turn.id
    }

    private fun updateTurn(id: Long, change: (Turn) -> Turn) =
        _state.update { s -> s.copy(turns = s.turns.map { if (it.id == id) change(it) else it }) }

    private fun setActivity(activity: Activity) = _state.update { it.copy(activity = activity) }

    private fun showMissing() = setStatus(ModelStatus.Missing(downloader.partialBytes()))

    private fun setStatus(status: ModelStatus) = _state.update { it.copy(modelStatus = status) }

    override fun onCleared() {
        recorder.requestStop()
        speaker.shutdown()
        engineScope.launch {
            mutex.withLock { release() }
            executor.shutdown()
        }.invokeOnCompletion { engineScope.cancel() }
    }
}

/** Spanish is a candidate too, so Walter speaking by mistake is recognised (§5.3). */
private val DETECTION_CANDIDATES = Languages.all + Languages.spanish

private fun isLowRam(app: Application): Boolean {
    val info = ActivityManager.MemoryInfo()
    app.getSystemService(ActivityManager::class.java).getMemoryInfo(info)
    return info.totalMem < LOW_RAM_BYTES
}
