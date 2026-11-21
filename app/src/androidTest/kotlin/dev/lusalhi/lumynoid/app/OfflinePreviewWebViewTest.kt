package dev.lusalhi.lumynoid.app

import android.webkit.WebView
import androidx.test.platform.app.InstrumentationRegistry
import dev.lusalhi.lumynoid.userland.WorkspacePreview
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit.SECONDS
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OfflinePreviewWebViewTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test
    fun localStylesAndOptedInScriptsRenderButPrivateFilesAndExternalNavigationDoNot() {
        val id = "preview-${UUID.randomUUID()}"
        val workspace = context.filesDir.resolve("workspaces/$id")
        val reader = WorkspacePreview(context, id)
        val document = "site/index.html"
        val privateFile = Files.createTempFile(context.filesDir.toPath(), "preview-private-", ".js")
        workspace.resolve("site").mkdirs()
        try {
            privateFile.toFile().writeText("document.title = 'private content loaded';")
            Files.createSymbolicLink(workspace.resolve("site/linked.js").toPath(), privateFile)
            workspace.resolve("site/local.css").writeText("#status { color: rgb(0, 128, 0) }")
            workspace.resolve("site/local.js").writeText("document.getElementById('status').textContent = 'local script ran';")
            workspace.resolve(document).writeText("""
                <!doctype html><meta charset="utf-8"><title>Offline fixture</title>
                <link rel="stylesheet" href="local.css">
                <p id="status">JavaScript off</p>
                <a id="outside" href="https://outside.invalid/page">External page</a>
                <script src="local.js"></script><script src="linked.js"></script>
                <script src="${privateFile.toUri()}"></script>
                <script src="content://dev.lusalhi.preview-does-not-exist/private.js"></script>
            """.trimIndent())
            val styleRequested = CountDownLatch(1)
            withPreview(document, false, { path ->
                reader.read(path).also { if (path == "site/local.css" && it != null) styleRequested.countDown() }
            }) { view ->
                assertTrue("local stylesheet was not served", styleRequested.await(10, SECONDS))
                instrumentation.runOnMainSync { assertFalse(view.settings.javaScriptEnabled) }
            }
            withPreview(document, true, reader::read) { view ->
                eventually {
                    evaluate(view, "document.getElementById('status')?.textContent") == "\"local script ran\""
                }
                assertEquals("\"rgb(0, 128, 0)\"", evaluate(view, "getComputedStyle(document.getElementById('status')).color"))
                assertEquals("\"Offline fixture\"", evaluate(view, "document.title"))
                val start = evaluate(view, "location.href")
                evaluate(view, "document.getElementById('outside').click()")
                assertEquals(start, evaluate(view, "location.href"))
            }
            withPreview(document, false, reader::read) { view ->
                instrumentation.runOnMainSync { assertFalse(view.settings.javaScriptEnabled) }
            }
        } finally {
            workspace.deleteRecursively()
            Files.deleteIfExists(privateFile)
        }
    }

    @Test
    fun unavailableDocumentSignalsTheNativeErrorView() {
        val unavailable = CountDownLatch(1)
        lateinit var view: WebView
        instrumentation.runOnMainSync {
            view = createOfflinePreviewWebView(context, "missing.html", false, { null }, unavailable::countDown)
        }
        try {
            assertTrue("A missing document must not display a blank success", unavailable.await(10, SECONDS))
        } finally {
            instrumentation.runOnMainSync { view.destroy() }
        }
    }

    private fun withPreview(path: String, javascript: Boolean, read: (String) -> ByteArray?, action: (WebView) -> Unit) {
        val unavailable = CountDownLatch(1)
        lateinit var view: WebView
        instrumentation.runOnMainSync {
            view = createOfflinePreviewWebView(context, path, javascript, read, unavailable::countDown)
        }
        try {
            action(view)
            assertEquals("The main document should have been available", 1L, unavailable.count)
        } finally {
            instrumentation.runOnMainSync { view.destroy() }
        }
    }

    private fun evaluate(view: WebView, script: String): String? {
        val result = AtomicReference<String>()
        val done = CountDownLatch(1)
        instrumentation.runOnMainSync {
            view.evaluateJavascript(script) { value ->
                result.set(value)
                done.countDown()
            }
        }
        check(done.await(5, SECONDS)) { "No response from WebView" }
        return result.get()
    }

    private fun eventually(check: () -> Boolean) {
        repeat(60) {
            if (check()) return
            Thread.sleep(100)
        }
        throw AssertionError("WebView did not render the local script")
    }
}
