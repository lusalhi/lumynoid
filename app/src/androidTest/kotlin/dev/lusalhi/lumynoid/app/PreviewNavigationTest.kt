package dev.lusalhi.lumynoid.app

import android.content.ClipboardManager
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import android.view.KeyEvent
import androidx.test.platform.app.InstrumentationRegistry
import dev.lusalhi.lumynoid.core.Origin
import dev.lusalhi.lumynoid.core.ProviderDraft
import dev.lusalhi.lumynoid.core.ProviderSaveResult
import dev.lusalhi.lumynoid.core.TaskEvent
import dev.lusalhi.lumynoid.core.TaskRequest
import dev.lusalhi.lumynoid.core.ToolArtifact
import dev.lusalhi.lumynoid.core.ToolCall
import dev.lusalhi.lumynoid.core.ToolOutcome
import dev.lusalhi.lumynoid.core.UiText
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class PreviewNavigationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext.applicationContext as LumynoidApp
    private val automation = instrumentation.uiAutomation

    @Test
    fun persistedArtifactsOpenInAppWithoutEgressEvenAfterOptIn() =
        ServerSocket(0, 8, InetAddress.getByName("127.0.0.1")).use(::verifyPreview)

    private fun verifyPreview(sink: ServerSocket) {
        val remote = "https://127.0.0.1:${sink.localPort}"
        if (Build.VERSION.SDK_INT >= 33) {
            automation.grantRuntimePermission(app.packageName, android.Manifest.permission.POST_NOTIFICATIONS)
        }
        val graph = app.graph
        val data = graph.data
        val notes = "Native preview text marker\n" + "αbeta".repeat(2000) + "\nEnd marker"
        val conversationId = runBlocking {
            // The connected test runs on a disposable install. No LLM request is sent.
            if (graph.providerAdmin.overview().active == null) {
                assertEquals(ProviderSaveResult.SAVED, graph.providerAdmin.save(ProviderDraft(
                    id = null, presetId = null, name = "Preview Fixture", baseUrl = "https://fixture.invalid/v1",
                    apiKey = "local-fixture", model = "preview-fixture", makeActive = true,
                )))
            }
            val id = data.conversations.create("Offline preview fixture").id
            val taskId = UUID.randomUUID().toString()
            val at = System.currentTimeMillis()
            fun eventId() = UUID.randomUUID().toString()
            val intro = "Published two preview files."
            val call = TaskEvent.RequestedToolCall(ToolCall("publish", "shell_create_file", "{}"), UiText("shell_create_file"))
            val files = listOf(ToolArtifact("notes.txt", "text/plain"), ToolArtifact("index.html", "text/html"))
            val directory = app.filesDir.resolve("workspaces/$id")
            directory.mkdirs()
            directory.resolve("notes.txt").writeText(notes)
            directory.resolve("index.html").writeText("""
                <!doctype html><h1 id="status">Workspace page marker</h1>
                <link rel="preconnect" href="$remote">
                <img src="$remote/image.png">
                <a href="$remote/page">External preview link</a>
                <script>
                    document.getElementById('status').textContent = 'Preview script marker';
                    fetch('$remote/request').catch(() => {});
                </script>
            """.trimIndent())
            data.events.createTask(taskId, TaskRequest(Origin.User, id, id, intro), at)
            data.events.append(id, TaskEvent.ToolCallRequested(eventId(), taskId, at, "", listOf(call)))
            data.events.append(id, TaskEvent.ToolResult(eventId(), taskId, at + 1, "publish", ToolOutcome(intro, artifacts = files, succeeded = true)))
            data.events.append(id, TaskEvent.TaskFinished(eventId(), taskId, at + 2))
            id
        }
        val activity = instrumentation.startActivitySync(Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        try {
            tap("notes.txt")
            await("Native preview text marker")
            tap(app.getString(R.string.preview_copy_file))
            val clipboard = app.getSystemService(ClipboardManager::class.java)!!
            instrumentation.runOnMainSync {
                assertEquals(notes, clipboard.primaryClip?.getItemAt(0)?.text?.toString())
                clipboard.clearPrimaryClip()
            }
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            await("Log")
            tap("Log")
            await(app.getString(R.string.action_log_title))
            tap(app.getString(R.string.tool_create_file))
            await(app.getString(R.string.tool_field_output))
            tap("index.html")
            await("Workspace page marker")
            tap("Enable JavaScript")
            await("Preview script marker")
            tap("External preview link")
            await("Preview script marker")
            tap("Close")
            await(app.getString(R.string.tool_field_output))
            tap("index.html")
            await("Workspace page marker")
            await("Enable JavaScript")
            tap("Close")
            await("index.html")
            await(app.getString(R.string.tool_field_output))
            sink.soTimeout = 750
            try {
                sink.accept().close()
                throw AssertionError("HTML preview reached the loopback sentinel")
            } catch (_: SocketTimeoutException) {
                // No HTTP connection, including speculative preconnect or the external link.
            }
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
            if (InstrumentationRegistry.getArguments().getString("keepPreviewFixture") != "true") {
                runBlocking { data.conversations.delete(conversationId) }
            }
        }
    }

    private fun tap(text: String) {
        val node = await(text)
        var target: AccessibilityNodeInfo? = node
        while (target != null && !target.isClickable) target = target.parent
        check(target?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true) { "Could not tap $text" }
    }

    private fun await(text: String): AccessibilityNodeInfo {
        val deadline = SystemClock.uptimeMillis() + 12_000
        var seen: List<String>
        do {
            val labels = mutableListOf<String>()
            val found = automation.rootInActiveWindow?.findVisible(text, labels)
            if (found != null) return found
            seen = labels
            SystemClock.sleep(100)
        } while (SystemClock.uptimeMillis() < deadline)
        throw AssertionError("Not visible: $text. Visible labels: $seen")
    }

    private fun AccessibilityNodeInfo.findVisible(label: String, seen: MutableList<String>): AccessibilityNodeInfo? {
        if (isVisibleToUser) {
            // Compose can merge descriptive text into a card; WebView can expose it as a description.
            for (value in listOfNotNull(text, contentDescription)) {
                seen += value.toString()
                if (label in value) return this
            }
        }
        for (index in 0 until childCount) getChild(index)?.findVisible(label, seen)?.let { return it }
        return null
    }
}
