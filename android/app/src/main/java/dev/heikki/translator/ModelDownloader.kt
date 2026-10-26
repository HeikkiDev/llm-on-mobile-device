package dev.heikki.translator

import android.content.Context
import android.net.ConnectivityManager
import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Locale

// The only model this app uses (SPEC.md §2.1). Change these together, never one alone.
const val MODEL_FILE = "gemma-4-E2B-it.litertlm"
const val MODEL_URL = "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/6e5c4f1e395deb959c494953478fa5cec4b8008f/gemma-4-E2B-it.litertlm"
const val MODEL_SIZE_BYTES = 2_588_147_712L
const val MODEL_SHA256 = "181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c"
const val VERIFY_SHA256 = true

private const val FREE_SPACE_MARGIN_BYTES = 200_000_000L
private const val BUFFER_BYTES = 1 shl 20
private const val TIMEOUT_MS = 30_000
private const val PROGRESS_INTERVAL_MS = 500L

const val NO_CONNECTION = "No connection. Check your internet and try again."
const val CORRUPTED = "Download corrupted, try again."

class DownloadException(message: String) : Exception(message)

enum class NetworkKind { None, Unmetered, Metered }

enum class ResponseAction { Append, Restart, Fail }

/** 206 = server honoured our Range header; 200 = it sent the whole file. */
fun responseAction(code: Int): ResponseAction = when (code) {
    HttpURLConnection.HTTP_PARTIAL -> ResponseAction.Append
    HttpURLConnection.HTTP_OK -> ResponseAction.Restart
    else -> ResponseAction.Fail
}

fun requiredFreeBytes(downloadedBytes: Long): Long =
    MODEL_SIZE_BYTES - downloadedBytes + FREE_SPACE_MARGIN_BYTES

fun httpErrorMessage(code: Int): String = when (code) {
    403, 404, 410, 429, in 500..599 -> "Model server unavailable, try later (HTTP $code)."
    else -> "Download failed (HTTP $code)."
}

fun formatGb(bytes: Long): String = String.format(Locale.US, "%.1f GB", bytes / 1e9)

fun networkKind(context: Context): NetworkKind {
    val cm = context.getSystemService(ConnectivityManager::class.java)
    return when {
        cm.activeNetwork == null -> NetworkKind.None
        cm.isActiveNetworkMetered -> NetworkKind.Metered
        else -> NetworkKind.Unmetered
    }
}

/** Resumable one-time download of the model into [dir] (app `filesDir`). Holds no UI state. */
class ModelDownloader(dir: File) {
    val modelFile = File(dir, MODEL_FILE)
    private val partFile = File(dir, "$MODEL_FILE.part")

    fun isModelValid(): Boolean = modelFile.length() == MODEL_SIZE_BYTES

    fun partialBytes(): Long = partFile.length()

    fun deleteModel() {
        modelFile.delete()
    }

    suspend fun download(onProgress: (Long) -> Unit, onVerifying: () -> Unit) = withContext(Dispatchers.IO) {
        if (partFile.length() > MODEL_SIZE_BYTES) partFile.delete()
        var done = partFile.length()

        val needed = requiredFreeBytes(done)
        if (partFile.parentFile!!.usableSpace < needed) {
            throw DownloadException("Not enough space. Free up about ${formatGb(needed)} and try again.")
        }

        if (done < MODEL_SIZE_BYTES) {
            val connection = URL(MODEL_URL).openConnection() as HttpURLConnection
            try {
                connection.instanceFollowRedirects = true
                connection.connectTimeout = TIMEOUT_MS
                connection.readTimeout = TIMEOUT_MS
                if (done > 0) connection.setRequestProperty("Range", "bytes=$done-")

                val code = connection.responseCode
                val append = when (responseAction(code)) {
                    ResponseAction.Append -> true
                    ResponseAction.Restart -> false
                    ResponseAction.Fail -> throw DownloadException(httpErrorMessage(code))
                }
                if (!append) done = 0

                connection.inputStream.use { input ->
                    FileOutputStream(partFile, append).use { output ->
                        val buffer = ByteArray(BUFFER_BYTES)
                        var lastReport = 0L
                        while (done <= MODEL_SIZE_BYTES) {
                            ensureActive()
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                            done += read
                            val now = SystemClock.elapsedRealtime()
                            if (now - lastReport >= PROGRESS_INTERVAL_MS) {
                                onProgress(done)
                                lastReport = now
                            }
                        }
                    }
                }
            } catch (e: IOException) {
                throw DownloadException(NO_CONNECTION)
            } finally {
                connection.disconnect()
            }
        }

        val length = partFile.length()
        onProgress(length)
        if (length > MODEL_SIZE_BYTES) {
            partFile.delete()
            throw DownloadException(CORRUPTED)
        }
        if (length < MODEL_SIZE_BYTES) throw DownloadException(NO_CONNECTION)

        if (VERIFY_SHA256) {
            onVerifying()
            if (sha256(partFile) != MODEL_SHA256) {
                partFile.delete()
                throw DownloadException(CORRUPTED)
            }
        }
        if (!partFile.renameTo(modelFile)) throw DownloadException("Could not save the model. Try again.")
    }

    private suspend fun sha256(file: File): String = withContext(Dispatchers.IO) {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(BUFFER_BYTES)
            while (true) {
                ensureActive()
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    }
}
