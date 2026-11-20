package dev.lusalhi.lumynoid.app

import java.io.File
import java.nio.file.Files
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

class CrashReportTest {
    private val env = CrashEnv("v0.1.0 (1)", "16 (API 37)", "Test Phone")
    private val key = "sk-live-SECRETKEY123"
    private val prompt = "my private prompt about taxes"

    private fun tempDir(): File = Files.createTempDirectory("crash-test").toFile().also { it.deleteOnExit() }

    private fun frames(n: Int) = Array(n) { StackTraceElement("com.example.C$it", "m$it", "C.kt", it + 1) }

    private fun secretThrowable(): Throwable {
        val cause = IllegalArgumentException("cause carries $prompt")
        cause.stackTrace = frames(2)
        val top = RuntimeException("top carries $key", cause)
        top.stackTrace = frames(3)
        top.addSuppressed(IllegalStateException("suppressed carries $key"))
        return top
    }

    @Test fun reportHasClassNamesAndFramesButNoMessages() {
        val text = formatCrashReport(env, 0L, "main", secretThrowable())
        assertTrue(text.contains("java.lang.RuntimeException"))
        assertTrue(text.contains("Caused by: java.lang.IllegalArgumentException"))
        assertTrue(text.contains("at com.example.C0.m0(C.kt:1)"))
        assertFalse(text.contains(key))
        assertFalse(text.contains(prompt))
        assertFalse(text.contains("suppressed"))
        assertFalse(text.contains("IllegalStateException"))
    }

    @Test fun reportHasHeaderFields() {
        val text = formatCrashReport(env, 0L, "worker-1", RuntimeException())
        assertTrue(text.contains("App: v0.1.0 (1)"))
        assertTrue(text.contains("Android: 16 (API 37)"))
        assertTrue(text.contains("Device: Test Phone"))
        assertTrue(text.contains("Time: 1970-01-01T00:00:00Z"))
        assertTrue(text.contains("Thread: worker-1"))
    }

    @Test fun hugeTraceIsCutAt100FramesWithTruncationLine() {
        val e = RuntimeException()
        e.stackTrace = frames(150)
        val text = formatCrashReport(env, 0L, "main", e)
        assertEquals(100, text.lines().count { it.startsWith("    at ") })
        assertTrue(text.contains("[truncated: 50 more frames not shown]"))
    }

    @Test fun shortTraceHasNoTruncationLine() {
        val e = RuntimeException()
        e.stackTrace = frames(100)
        assertFalse(formatCrashReport(env, 0L, "main", e).contains("truncated"))
    }

    @Test fun longCauseChainIsCutAtFiveCauses() {
        var e: Throwable = RuntimeException("c9")
        repeat(8) { e = RuntimeException("c", e) }
        val text = formatCrashReport(env, 0L, "main", e)
        assertEquals(5, text.lines().count { it.startsWith("Caused by: ") })
        assertTrue(text.contains("[truncated: more causes not shown]"))
    }

    @Test fun causeCycleTerminates() {
        val a = RuntimeException("a")
        val b = RuntimeException("b", a)
        a.initCause(b)
        val text = formatCrashReport(env, 0L, "main", a)
        assertTrue(text.contains("[truncated: cause chain loops back]"))
    }

    @Test fun writeKeepsOnlyNewestFive() {
        val store = CrashStore(tempDir())
        val files = (1..8).map { store.write("r$it", it.toLong()) }
        assertEquals(8, files.size)
        assertEquals("r8", store.newest()!!.readText())
        assertEquals(5, files.count { it.exists() })
        assertFalse(files[2].exists())
        assertTrue(files[3].exists())
    }

    @Test fun sameMillisecondCrashesDoNotOverwrite() {
        val store = CrashStore(tempDir())
        val a = store.write("first", 1000L)
        val b = store.write("second", 1000L)
        assertNotEquals(a.name, b.name)
        assertEquals("first", a.readText())
        assertEquals("second", store.newest()!!.readText())
    }

    @Test fun namesSortByTimeAcrossDigitCounts() {
        val store = CrashStore(tempDir())
        store.write("old", 999L)
        store.write("new", 1_000_000L)
        assertEquals("new", store.newest()!!.readText())
    }

    @Test fun newestIsNullWhenEmptyOrMissingDir() {
        assertNull(CrashStore(tempDir()).newest())
        assertNull(CrashStore(File(tempDir(), "missing")).newest())
    }

    @Test fun clearDeletesAllReports() {
        val store = CrashStore(tempDir())
        repeat(3) { store.write("r", it.toLong()) }
        store.clear()
        assertNull(store.newest())
    }

    @Test fun exportCopiesIntoEmptiedDirectory() {
        val store = CrashStore(tempDir())
        val report = store.write("body", 5L)
        val export = tempDir()
        File(export, "stale.txt").writeText("old")
        val copy = copyForExport(report, export)
        assertEquals("body", copy.readText())
        assertEquals(export, copy.parentFile)
        assertEquals(listOf(copy.name), export.list()!!.toList())
        assertTrue(report.exists())
    }

    @Test fun handlerWritesReportWithoutSecretsThenChains() {
        val store = CrashStore(tempDir())
        var chained: Throwable? = null
        val error = secretThrowable()
        val handler = crashRecordingHandler(store, env, { 42L }, { _, e -> chained = e })
        handler.uncaughtException(Thread.currentThread(), error)
        val text = store.newest()!!.readText()
        assertFalse(text.contains(key))
        assertFalse(text.contains(prompt))
        assertTrue(text.contains("java.lang.RuntimeException"))
        assertSame(error, chained)
    }

    @Test fun handlerSwallowsWriteFailureAndStillChains() {
        // A regular file where the directory should be makes every write fail.
        val blocker = File.createTempFile("crash-blocker", ".tmp").also { it.deleteOnExit() }
        val store = CrashStore(File(blocker, "crash"))
        var chained = false
        val handler = crashRecordingHandler(store, env, { 1L }, { _, _ -> chained = true })
        handler.uncaughtException(Thread.currentThread(), RuntimeException())
        assertTrue(chained)
    }

    @Test fun handlerSurvivesFailingClockAndNullPrevious() {
        val handler = crashRecordingHandler(CrashStore(tempDir()), env, { error("clock") }, null)
        handler.uncaughtException(Thread.currentThread(), RuntimeException())
    }

    @Test fun filePathsHasExactlyOneCachePathExport() {
        val paths = parse("src/main/res/xml/file_paths.xml").documentElement
        val children = (0 until paths.childNodes.length).map { paths.childNodes.item(it) }.filterIsInstance<Element>()
        assertEquals(1, children.size)
        assertEquals("cache-path", children[0].tagName)
        assertEquals("export", children[0].getAttribute("name"))
        assertEquals("export/", children[0].getAttribute("path"))
    }

    @Test fun mainManifestDeclaresOneSafeFileProvider() {
        val doc = parse("src/main/AndroidManifest.xml")
        val providers = doc.getElementsByTagName("provider")
        assertEquals(1, providers.length)
        val p = providers.item(0) as Element
        assertEquals("androidx.core.content.FileProvider", p.getAttribute("android:name"))
        assertEquals("\${applicationId}.files", p.getAttribute("android:authorities"))
        assertEquals("false", p.getAttribute("android:exported"))
        assertEquals("true", p.getAttribute("android:grantUriPermissions"))
        // The release manifest gains no permission (AD-24) and no crash-test entry.
        val text = File("src/main/AndroidManifest.xml").readText()
        assertFalse(text.contains("CrashTest"))
        val permissions = (0 until doc.getElementsByTagName("uses-permission").length)
            .map { (doc.getElementsByTagName("uses-permission").item(it) as Element).getAttribute("android:name") }
        assertEquals(
            setOf(
                "android.permission.INTERNET",
                "android.permission.FOREGROUND_SERVICE",
                "android.permission.FOREGROUND_SERVICE_SPECIAL_USE",
                "android.permission.WAKE_LOCK",
            ),
            permissions.toSet(),
        )
    }

    private fun parse(path: String) =
        DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File(path))
}
