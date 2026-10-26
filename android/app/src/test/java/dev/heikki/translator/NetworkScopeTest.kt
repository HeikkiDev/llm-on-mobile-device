package dev.heikki.translator

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/** SPEC §8: network code lives only in ModelDownloader.kt and no networking/analytics libraries are used. */
class NetworkScopeTest {
    // Gradle runs unit tests with the module directory (android/app) as working directory.
    private val app = File(System.getProperty("user.dir")!!)
    private val android = app.parentFile!!
    private val main = File(app, "src/main")

    private fun sources(root: File, extensions: Set<String>) = root.walkTopDown()
        .onEnter { it.name !in setOf("build", ".gradle", ".kotlin", ".idea", "test") }
        .filter { it.isFile && it.extension in extensions }
        .toList()

    private fun filesMatching(files: List<File>, regex: Regex) =
        files.filter { regex.containsMatchIn(it.readText()) }.map { it.name }.toSet()

    @Test
    fun networkApisAppearOnlyInModelDownloader() {
        val kotlin = sources(main, setOf("kt", "java"))
        val regex = Regex("""\b(HttpURLConnection|URLConnection|Socket)\b|java\.net\.URL\b""")
        assertEquals(setOf("ModelDownloader.kt"), filesMatching(kotlin, regex))
    }

    @Test
    fun noNetworkingOrAnalyticsLibrariesAnywhere() {
        val files = sources(android, setOf("kt", "kts", "java", "toml", "xml", "properties", "pro"))
        val regex = Regex("okhttp|ktor|retrofit|volley|DownloadManager|WorkManager|firebase|analytics", RegexOption.IGNORE_CASE)
        assertEquals(emptySet<String>(), filesMatching(files, regex))
    }

    @Test
    fun onlyUrlIsModelUrl() {
        val lines = sources(main, setOf("kt", "java", "xml")).flatMap { f ->
            f.readLines().filter { "https://" in it }.map { "${f.name}: ${it.trim()}" }
        }
        assertEquals(1, lines.size)
        assertEquals(true, lines.single().startsWith("ModelDownloader.kt: const val MODEL_URL ="))
    }

    @Test
    fun manifestRequestsOnlyInternetAndNetworkState() {
        val permissions = Regex("""<uses-permission[^>]*android:name="([^"]+)"""")
            .findAll(File(main, "AndroidManifest.xml").readText()).map { it.groupValues[1] }.toSet()
        assertEquals(setOf("android.permission.INTERNET", "android.permission.ACCESS_NETWORK_STATE"), permissions)
    }
}
