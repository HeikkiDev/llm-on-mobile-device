package dev.heikki.translator

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import kotlin.coroutines.coroutineContext
import kotlin.math.sqrt

// Gemma 4 audio input: 16 kHz mono, at most 30 s (model card). SPEC-WALTER §4.1.
const val SAMPLE_RATE = 16_000
const val MAX_RECORDING_MS = 30_000
const val SILENCE_TO_STOP_MS = 2_500
const val NO_SPEECH_TIMEOUT_MS = 8_000
const val FRAME_MS = 30
const val FRAME_SAMPLES = SAMPLE_RATE * FRAME_MS / 1000

// VAD tuning (16-bit RMS). Revisit on site (SPEC-WALTER W5).
private const val SPEECH_FACTOR = 3.0
private const val MIN_SPEECH_RMS = 250.0
private const val SPEECH_FRAMES_TO_START = 3
private const val PADDING_MS = 300
private const val MIN_MANUAL_MS = 500

sealed interface Recording {
    data class Speech(val wav: ByteArray, val durationMs: Int) : Recording
    data object NoSpeech : Recording
}

enum class GateEvent { Continue, Stop, NoSpeech }

/**
 * Energy-based voice activity detection over 30 ms frames. The noise floor follows the quietest
 * recent frames; "speech" is a few consecutive frames well above it. Pure, so it is unit-tested.
 */
class SpeechGate {
    var frames = 0; private set
    var firstSpeechFrame = -1; private set
    var lastSpeechFrame = -1; private set
    private var floor = -1.0
    private var loudRun = 0

    val heardSpeech get() = firstSpeechFrame >= 0

    fun accept(rms: Double): GateEvent {
        frames++
        if (floor < 0 || rms < floor) floor = rms
        val loud = rms > maxOf(floor * SPEECH_FACTOR, MIN_SPEECH_RMS)
        // Adapt upwards only on quiet frames, so long speech never raises the floor above itself.
        if (!loud) floor += (rms - floor) * 0.05
        loudRun = if (loud) loudRun + 1 else 0
        if (loudRun >= SPEECH_FRAMES_TO_START) {
            if (firstSpeechFrame < 0) firstSpeechFrame = frames - SPEECH_FRAMES_TO_START
            lastSpeechFrame = frames - 1
        }
        val elapsedMs = frames * FRAME_MS
        return when {
            elapsedMs >= MAX_RECORDING_MS -> if (heardSpeech) GateEvent.Stop else GateEvent.NoSpeech
            !heardSpeech && elapsedMs >= NO_SPEECH_TIMEOUT_MS -> GateEvent.NoSpeech
            heardSpeech && (frames - 1 - lastSpeechFrame) * FRAME_MS >= SILENCE_TO_STOP_MS -> GateEvent.Stop
            else -> GateEvent.Continue
        }
    }

    /** Sample range to keep: speech plus a little padding, or everything if speech was never detected. */
    fun keptRange(totalSamples: Int): IntRange {
        if (!heardSpeech) return 0 until totalSamples
        val pad = PADDING_MS / FRAME_MS
        val start = maxOf(0, firstSpeechFrame - pad) * FRAME_SAMPLES
        val end = minOf(totalSamples, (lastSpeechFrame + 1 + pad) * FRAME_SAMPLES)
        return start until end
    }
}

fun rms(frame: ShortArray, count: Int = frame.size): Double {
    if (count == 0) return 0.0
    var sum = 0.0
    for (i in 0 until count) sum += frame[i].toDouble() * frame[i]
    return sqrt(sum / count)
}

/** 16-bit PCM mono WAV (44-byte header, little endian). */
fun wav(samples: ShortArray, sampleRate: Int = SAMPLE_RATE): ByteArray {
    val dataBytes = samples.size * 2
    val out = ByteArrayOutputStream(44 + dataBytes)
    fun int(v: Int) = repeat(4) { out.write(v ushr (8 * it) and 0xFF) }
    fun short(v: Int) = repeat(2) { out.write(v ushr (8 * it) and 0xFF) }
    out.write("RIFF".toByteArray()); int(36 + dataBytes); out.write("WAVE".toByteArray())
    out.write("fmt ".toByteArray()); int(16); short(1); short(1); int(sampleRate); int(sampleRate * 2); short(2); short(16)
    out.write("data".toByteArray()); int(dataBytes)
    for (s in samples) short(s.toInt())
    return out.toByteArray()
}

/** Records one turn from the microphone. Needs RECORD_AUDIO (checked by the caller). */
class AudioRecorder {
    @Volatile private var stopRequested = false

    /** Tap-to-stop: ends the current recording and keeps what was said. */
    fun requestStop() {
        stopRequested = true
    }

    @SuppressLint("MissingPermission")
    suspend fun record(onLevel: (level: Float, elapsedMs: Int) -> Unit): Recording = withContext(Dispatchers.IO) {
        stopRequested = false
        val minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val record = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION, SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBuffer, FRAME_SAMPLES * 2 * 4),
        )
        check(record.state == AudioRecord.STATE_INITIALIZED) { "No se puede usar el micrófono." }
        val all = ShortArray(SAMPLE_RATE * MAX_RECORDING_MS / 1000)
        var total = 0
        val gate = SpeechGate()
        val frame = ShortArray(FRAME_SAMPLES)
        try {
            record.startRecording()
            while (true) {
                coroutineContext.ensureActive()
                var filled = 0
                while (filled < FRAME_SAMPLES) {
                    val n = record.read(frame, filled, FRAME_SAMPLES - filled)
                    check(n >= 0) { "Error del micrófono ($n)." }
                    filled += n
                }
                val count = minOf(FRAME_SAMPLES, all.size - total)
                frame.copyInto(all, total, 0, count)
                total += count
                val level = rms(frame)
                val event = gate.accept(level)
                onLevel((level / 4000.0).coerceIn(0.0, 1.0).toFloat(), gate.frames * FRAME_MS)
                if (stopRequested) break
                if (event == GateEvent.NoSpeech) return@withContext Recording.NoSpeech
                if (event == GateEvent.Stop) break
            }
        } finally {
            record.stop()
            record.release()
        }
        if (!gate.heardSpeech && total * 1000 / SAMPLE_RATE < MIN_MANUAL_MS) return@withContext Recording.NoSpeech
        val range = gate.keptRange(total)
        val kept = all.copyOfRange(range.first, range.last + 1)
        Recording.Speech(wav(kept), kept.size * 1000 / SAMPLE_RATE)
    }
}
