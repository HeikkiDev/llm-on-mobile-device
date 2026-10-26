package dev.heikki.translator

import android.Manifest
import android.content.pm.PackageManager
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Runs without the model. A model already on the device (e.g. a dev emulator) is moved aside and restored,
 * so run with `adb shell am instrument` (connectedAndroidTest uninstalls the app and would delete it).
 */
@RunWith(AndroidJUnit4::class)
class AppTest {
    @get:Rule val compose = createEmptyComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val files = context.filesDir
    private val moved = mutableListOf<Pair<File, File>>()

    @Before
    fun moveModelAside() {
        for (name in listOf(MODEL_FILE, "$MODEL_FILE.part")) {
            val file = File(files, name)
            val backup = File(files, "$name.test-backup")
            if (file.exists()) {
                check(file.renameTo(backup)) { "Could not move $name aside" }
                moved += file to backup
            }
        }
    }

    @After
    fun restoreModel() {
        val dummy = File(files, MODEL_FILE)
        if (dummy.length() != MODEL_SIZE_BYTES) dummy.delete()
        moved.forEach { (file, backup) -> backup.renameTo(file) }
    }

    @Test
    fun showsDownloadStateWithoutModel() {
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.onNodeWithText("Download the language model").assertIsDisplayed()
            compose.onNodeWithText("Download").assertIsDisplayed()
        }
    }

    @Test
    fun wrongSizeModelStillShowsDownloadState() {
        File(files, MODEL_FILE).writeBytes(ByteArray(1024))
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.onNodeWithText("Download").assertIsDisplayed()
        }
    }

    @Test
    fun requestsOnlyInternetAndNetworkState() {
        @Suppress("DEPRECATION")
        val requested = context.packageManager
            .getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
            .requestedPermissions.orEmpty().toSet()
        // AndroidX adds this signature-level, app-private permission; it is not a network permission.
        val own = "${context.packageName}.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION"
        assertEquals(setOf(Manifest.permission.INTERNET, Manifest.permission.ACCESS_NETWORK_STATE), requested - own)
    }
}
