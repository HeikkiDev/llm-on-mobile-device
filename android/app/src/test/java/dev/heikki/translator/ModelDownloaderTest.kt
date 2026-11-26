package dev.heikki.translator

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile

class ModelDownloaderTest {
    @Test
    fun responseCodeDecidesHowToWrite() {
        assertEquals(ResponseAction.Append, responseAction(206))
        assertEquals(ResponseAction.Restart, responseAction(200))
        assertEquals(ResponseAction.Fail, responseAction(416))
        assertEquals(ResponseAction.Fail, responseAction(403))
    }

    @Test
    fun freeSpaceAccountsForAlreadyDownloadedBytes() {
        assertEquals(MODEL_SIZE_BYTES + 200_000_000L, requiredFreeBytes(0))
        assertEquals(200_000_000L, requiredFreeBytes(MODEL_SIZE_BYTES))
    }

    @Test
    fun completePartNeedsNoNetwork() {
        assertTrue(needsNetwork(0))
        assertTrue(needsNetwork(MODEL_SIZE_BYTES - 1))
        assertFalse(needsNetwork(MODEL_SIZE_BYTES))
    }

    @Test
    fun serverErrorsAskToTryLater() {
        assertEquals("El servidor del modelo no está disponible. Prueba más tarde (HTTP 503).", httpErrorMessage(503))
        assertEquals("La descarga ha fallado (HTTP 400).", httpErrorMessage(400))
    }

    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun modelIsValidOnlyWithTheExactSize() {
        val downloader = ModelDownloader(tmp.root)
        assertFalse(downloader.isModelValid())
        RandomAccessFile(downloader.modelFile, "rw").use { it.setLength(1024) }
        assertFalse(downloader.isModelValid())
        RandomAccessFile(downloader.modelFile, "rw").use { it.setLength(MODEL_SIZE_BYTES) } // sparse, no disk cost
        assertTrue(downloader.isModelValid())
    }

    private fun copyFailure(input: InputStream, output: OutputStream): String? =
        try {
            runBlocking { copyChunks(input, output, 0) {} }
            null
        } catch (e: DownloadException) {
            e.message
        }

    @Test
    fun copyReportsBytesDone() {
        val done = runBlocking { copyChunks(ByteArrayInputStream(ByteArray(3_000_000)), ByteArrayOutputStream(), 10) {} }
        assertEquals(3_000_010L, done)
    }

    @Test
    fun failedReadMeansNoConnection() {
        val dropped = object : InputStream() {
            override fun read(): Int = throw IOException("connection reset")
        }
        assertEquals(NO_CONNECTION, copyFailure(dropped, ByteArrayOutputStream()))
    }

    @Test
    fun failedWriteMeansStorageNotNetwork() {
        val full = object : OutputStream() {
            override fun write(b: Int) = throw IOException("No space left on device")
        }
        assertEquals(STORAGE_ERROR, copyFailure(ByteArrayInputStream(ByteArray(10)), full))
    }
}
