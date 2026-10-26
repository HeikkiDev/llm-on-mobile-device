package dev.heikki.translator

import android.app.ActivityManager
import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import android.content.Context
import com.google.ai.edge.litertlm.Backend
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.Executors

private val BACKEND: Backend = Backend.CPU()
private const val MAX_NUM_TOKENS = 4096
private const val LOW_RAM_BYTES = 7_500_000_000L
const val MAX_INPUT_CHARS = 1000
private const val PREFS = "settings"
private const val PREF_TARGET = "target"

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

data class UiState(
    val modelStatus: ModelStatus = ModelStatus.Missing(0),
    val lowRam: Boolean = false,
    val input: String = "",
    val target: Language = Languages.default(),
    val output: String = "",
    val translating: Boolean = false,
    val error: String? = null,
)

class TranslatorViewModel(app: Application) : AndroidViewModel(app) {
    private val downloader = ModelDownloader(app.filesDir)

    // Single owner of the Engine (litert-lm skill): every engine call runs on this one thread.
    private val executor = Executors.newSingleThreadExecutor()
    private val engineScope = CoroutineScope(SupervisorJob() + executor.asCoroutineDispatcher())
    private val mutex = Mutex()
    private var engine: Engine? = null
    private var conversation: Conversation? = null
    private var streaming = false // touched only on the executor thread
    @Volatile private var stopRequested = false
    private var downloadJob: Job? = null
    private var cellularAccepted = false

    private val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(
        UiState(
            lowRam = isLowRam(app),
            target = Languages.byCode(prefs.getString(PREF_TARGET, null)) ?: Languages.default(),
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
                setStatus(ModelStatus.Error(e.message ?: "Download failed. Try again."))
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
                    setStatus(ModelStatus.Error(e.message ?: "Could not load the model.", loadFailed = true))
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
            maxNumTokens = MAX_NUM_TOKENS,
            cacheDir = getApplication<Application>().cacheDir.path,
        )
        // Store the engine only after initialize() returns (never close a failed one).
        return Engine(config).also {
            it.initialize()
            engine = it
        }
    }

    fun onInputChange(text: String) = _state.update { it.copy(input = text) }

    fun onTargetChange(target: Language) {
        prefs.edit().putString(PREF_TARGET, target.code).apply()
        _state.update { it.copy(target = target) }
    }

    fun clear() = _state.update { it.copy(input = "", output = "") }

    fun errorShown() = _state.update { it.copy(error = null) }

    fun translate() {
        val current = _state.value
        val text = current.input.trim()
        if (current.modelStatus != ModelStatus.Ready || current.translating) return
        if (text.isEmpty() || text.length > MAX_INPUT_CHARS) return
        val target = current.target
        _state.update { it.copy(translating = true, output = "", error = null) }
        engineScope.launch {
            try {
                // Single-shot: a fresh conversation per translation keeps history out of the prompt.
                val conv = mutex.withLock {
                    conversation?.close()
                    conversation = null
                    loadEngine().createConversation(
                        ConversationConfig(
                            systemInstruction = Contents.of(buildSystemInstruction(target)),
                            samplerConfig = SamplerConfig(topK = 1, topP = 1.0, temperature = 0.0),
                            thinkingConfig = ThinkingConfig(enableThinking = false),
                        )
                    ).also {
                        conversation = it
                        streaming = true
                    }
                }
                conv.sendMessageAsync(text).collect { chunk ->
                    _state.update { it.copy(output = it.output + chunk.toString()) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (!stopRequested) _state.update { it.copy(error = e.message ?: "Translation failed.") }
            } finally {
                streaming = false
                stopRequested = false
                _state.update { it.copy(output = cleanTranslation(it.output, target), translating = false) }
            }
        }
    }

    fun stop() {
        if (!_state.value.translating) return
        stopRequested = true
        engineScope.launch { conversation?.cancelProcess() }
    }

    /** Frees ~2 GB under memory pressure; the next translation reloads the engine lazily. */
    fun releaseIfIdle() {
        if (_state.value.translating) return
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

    private fun showMissing() = setStatus(ModelStatus.Missing(downloader.partialBytes()))

    private fun setStatus(status: ModelStatus) = _state.update { it.copy(modelStatus = status) }

    override fun onCleared() {
        engineScope.launch {
            mutex.withLock { release() }
            executor.shutdown()
        }.invokeOnCompletion { engineScope.cancel() }
    }
}

private fun isLowRam(app: Application): Boolean {
    val info = ActivityManager.MemoryInfo()
    app.getSystemService(ActivityManager::class.java).getMemoryInfo(info)
    return info.totalMem < LOW_RAM_BYTES
}
