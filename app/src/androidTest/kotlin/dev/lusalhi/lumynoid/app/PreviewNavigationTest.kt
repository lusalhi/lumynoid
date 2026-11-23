package dev.lusalhi.lumynoid.app

import android.content.ClipboardManager
import android.content.Intent
import android.database.sqlite.SQLiteDatabase
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
import dev.lusalhi.lumynoid.userland.UserlandRuntime
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.util.UUID
import kotlinx.coroutines.flow.first
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
        val graph = app.graph
        val data = graph.data
        var otherProjectId: String? = null
        var fixtureConversationId: String? = null
        var mainActivity: MainActivity? = null
        var testFailure: Throwable? = null
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                automation.grantRuntimePermission(app.packageName, android.Manifest.permission.POST_NOTIFICATIONS)
            }
            val notes = "Native preview text marker\n" + "αbeta".repeat(2000) + "\nEnd marker"
            lateinit var html: ArtifactIdentity
            val otherConversation = runBlocking {
                // The connected test runs on a disposable install. No LLM request is sent.
                if (graph.providerAdmin.overview().active == null) {
                    assertEquals(ProviderSaveResult.SAVED, graph.providerAdmin.save(ProviderDraft(
                        id = null, presetId = null, name = "Preview Fixture", baseUrl = "https://fixture.invalid/v1",
                        apiKey = "local-fixture", model = "preview-fixture", makeActive = true,
                    )))
                }
                val other = checkNotNull(data.projects.create("Unrelated preview project"))
                otherProjectId = other.id
                val otherId = data.conversations.create("Unrelated preview chat", other.id).id
                app.filesDir.resolve("workspaces/${other.workspaceId}").apply {
                    mkdirs()
                    resolve("index.html").writeText("<h1>Unrelated workspace marker</h1>")
                }
                val id = data.conversations.create("Offline preview fixture").id
                fixtureConversationId = id
                val resultId = UUID.randomUUID().toString()
                val taskId = UUID.randomUUID().toString()
                val at = System.currentTimeMillis()
                fun eventId() = UUID.randomUUID().toString()
                val intro = "Published two preview files."
                val call = TaskEvent.RequestedToolCall(ToolCall("publish", "shell_create_file", "{}"), UiText("shell_create_file"))
                val files = listOf(
                    ToolArtifact("notes.txt", "text/plain"),
                    ToolArtifact("index.html", "text/html"),
                    ToolArtifact("../$id-outside.html", "text/html"),
                )
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
                directory.parentFile!!.resolve("$id-outside.html").writeText("<h1>Outside workspace marker</h1>")
                data.events.createTask(taskId, TaskRequest(Origin.User, id, intro), at, id)
                data.events.append(id, TaskEvent.ToolCallRequested(eventId(), taskId, at, "", listOf(call)))
                data.events.append(id, TaskEvent.ToolResult(resultId, taskId, at + 1, "publish", ToolOutcome(intro, artifacts = files, succeeded = true)))
                data.events.append(id, TaskEvent.TaskFinished(eventId(), taskId, at + 2))
                // Seed the persisted state left by the Epic 2 schema migration before filesystem adoption.
                SQLiteDatabase.openDatabase(app.getDatabasePath("lumynoid.db").path, null, SQLiteDatabase.OPEN_READWRITE).use {
                    it.execSQL("UPDATE conversation SET legacy_workspace_pending = 1 WHERE id = ?", arrayOf(id))
                }
                html = ArtifactIdentity(id, resultId, files[1].path, files[1].mediaType)
                otherId
            }
            assertUnavailable(html, 1)
            runBlocking {
                data.adoptLegacyWorkspaces("Untitled Project") { pendingId ->
                    if (pendingId == html.conversationId) UserlandRuntime.legacyWorkspaceHasData(app, pendingId) else null
                }
            }
            mainActivity = instrumentation.startActivitySync(
                Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            ) as MainActivity
            assertUnavailable(html.copy(conversationId = otherConversation), 1)
            await("notes.txt")
            assertUnavailable(html, 2)
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
        } catch (failure: Throwable) {
            testFailure = failure
            throw failure
        } finally {
            runCatching {
                try {
                    mainActivity?.let { activity ->
                        instrumentation.runOnMainSync { activity.finish() }
                    }
                    instrumentation.waitForIdleSync()
                } finally {
                    if (InstrumentationRegistry.getArguments().getString("keepPreviewFixture") != "true") {
                        runBlocking {
                            try {
                                fixtureConversationId?.let { id ->
                                    try {
                                        // Teardown owns this ID; settle a failed probe as plain before removing the fixture.
                                        data.adoptLegacyWorkspaces("Untitled Project") { pendingId ->
                                            if (pendingId == id) false else null
                                        }
                                        val projectId = data.conversations.observeAll().first().firstOrNull { it.id == id }?.projectId
                                        if (projectId != null) {
                                            check(data.projects.delete(projectId)) {
                                                "Could not delete the adopted preview project fixture."
                                            }
                                        } else {
                                            check(data.conversations.delete(id)) {
                                                "Could not delete the unadopted preview conversation fixture."
                                            }
                                            UserlandRuntime.deleteWorkspace(app, id)
                                        }
                                    } finally {
                                        app.filesDir.resolve("workspaces/$id-outside.html").delete()
                                    }
                                }
                            } finally {
                                otherProjectId?.let { id ->
                                    check(data.projects.delete(id)) {
                                        "Could not delete the unrelated preview project fixture."
                                    }
                                }
                            }
                        }
                    }
                }
            }.onFailure { cleanupFailure ->
                val original = testFailure ?: throw cleanupFailure
                original.addSuppressed(cleanupFailure)
            }
        }
    }

    private fun assertUnavailable(artifact: ArtifactIdentity, index: Int) {
        app.startActivity(ArtifactHtmlActivity.intent(app, artifact, index).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        try {
            await(app.getString(R.string.preview_unavailable))
            tap("Close")
        } catch (failure: Throwable) {
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            throw failure
        } finally {
            instrumentation.waitForIdleSync()
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
