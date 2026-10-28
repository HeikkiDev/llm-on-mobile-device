package dev.heikki.translator

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class AudioRecorderTest {
    private fun feed(gate: SpeechGate, rms: Double, ms: Int): GateEvent {
        var event = GateEvent.Continue
        repeat(ms / FRAME_MS) { event = gate.accept(rms).takeIf { event == GateEvent.Continue } ?: event }
        return event
    }

    @Test
    fun silenceOnlyIsNoSpeechAfterTimeout() {
        val gate = SpeechGate()
        assertEquals(GateEvent.Continue, feed(gate, 50.0, NO_SPEECH_TIMEOUT_MS - 2 * FRAME_MS))
        assertEquals(GateEvent.NoSpeech, feed(gate, 50.0, 4 * FRAME_MS))
    }

    @Test
    fun stopsAfterSilenceWindowNotDuringShortPauses() {
        val gate = SpeechGate()
        feed(gate, 50.0, 600)
        assertEquals(GateEvent.Continue, feed(gate, 3000.0, 2000))
        assertEquals(GateEvent.Continue, feed(gate, 50.0, 1500)) // a natural pause
        assertEquals(GateEvent.Continue, feed(gate, 3000.0, 1000))
        assertEquals(GateEvent.Continue, feed(gate, 50.0, SILENCE_TO_STOP_MS - 3 * FRAME_MS))
        assertEquals(GateEvent.Stop, feed(gate, 50.0, 5 * FRAME_MS))
    }

    @Test
    fun longSpeechNeverStopsBeforeLimit() {
        val gate = SpeechGate()
        feed(gate, 50.0, 300)
        assertEquals(GateEvent.Continue, feed(gate, 3000.0, 20_000))
        assertEquals(GateEvent.Stop, feed(gate, 3000.0, MAX_RECORDING_MS))
    }

    @Test
    fun keepsSpeechPlusPaddingOnly() {
        val gate = SpeechGate()
        feed(gate, 50.0, 3000)
        feed(gate, 3000.0, 1500)
        feed(gate, 50.0, SILENCE_TO_STOP_MS)
        val total = gate.frames * FRAME_SAMPLES
        val range = gate.keptRange(total)
        val keptMs = (range.last + 1 - range.first) * 1000 / SAMPLE_RATE
        assertTrue("kept $keptMs ms", keptMs in 1500..2200)
        assertTrue(range.first >= (3000 - 400) * SAMPLE_RATE / 1000)
    }

    @Test
    fun wavHeaderIsPcm16Mono16k() {
        val bytes = wav(ShortArray(10) { it.toShort() })
        assertEquals(44 + 20, bytes.size)
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals("RIFF", String(bytes, 0, 4))
        assertEquals(36 + 20, b.getInt(4))
        assertEquals("WAVEfmt ", String(bytes, 8, 8))
        assertEquals(16, b.getInt(16))
        assertEquals(1.toShort(), b.getShort(20)) // PCM
        assertEquals(1.toShort(), b.getShort(22)) // mono
        assertEquals(SAMPLE_RATE, b.getInt(24))
        assertEquals(SAMPLE_RATE * 2, b.getInt(28))
        assertEquals(16.toShort(), b.getShort(34))
        assertEquals("data", String(bytes, 36, 4))
        assertEquals(20, b.getInt(40))
        assertEquals(9.toShort(), b.getShort(44 + 18))
    }

    @Test
    fun rmsOfConstantSignal() {
        assertEquals(1000.0, rms(ShortArray(FRAME_SAMPLES) { 1000 }), 0.001)
        assertEquals(0.0, rms(ShortArray(0)), 0.0)
    }
}
