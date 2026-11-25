package dev.heikki.translator

import android.content.ComponentCallbacks2
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@Suppress("DEPRECATION")
class TrimMemoryTest {
    @Test
    fun releasesOnTheLevelsCurrentAndroidStillSends() {
        assertTrue(shouldReleaseOnTrim(ComponentCallbacks2.TRIM_MEMORY_BACKGROUND))
        assertTrue(shouldReleaseOnTrim(ComponentCallbacks2.TRIM_MEMORY_MODERATE))
        assertTrue(shouldReleaseOnTrim(ComponentCallbacks2.TRIM_MEMORY_COMPLETE))
    }

    @Test
    fun stillReleasesOnRunningCriticalForOlderAndroid() {
        assertTrue(shouldReleaseOnTrim(ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL))
    }

    @Test
    fun keepsTheEngineWhileTheUserIsInTheApp() {
        assertFalse(shouldReleaseOnTrim(ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE))
        assertFalse(shouldReleaseOnTrim(ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW))
        assertFalse(shouldReleaseOnTrim(ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN))
    }
}
