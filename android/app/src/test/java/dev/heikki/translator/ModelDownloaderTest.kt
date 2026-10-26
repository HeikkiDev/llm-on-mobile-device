package dev.heikki.translator

import org.junit.Assert.assertEquals
import org.junit.Test

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
    fun serverErrorsAskToTryLater() {
        assertEquals("Model server unavailable, try later (HTTP 503).", httpErrorMessage(503))
        assertEquals("Download failed (HTTP 400).", httpErrorMessage(400))
    }
}
