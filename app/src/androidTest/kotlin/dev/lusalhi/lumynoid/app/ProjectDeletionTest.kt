package dev.lusalhi.lumynoid.app

import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.system.Os
import android.view.accessibility.AccessibilityNodeInfo
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import dev.lusalhi.lumynoid.core.Origin
import dev.lusalhi.lumynoid.core.Project
import dev.lusalhi.lumynoid.core.ProviderDraft
import dev.lusalhi.lumynoid.core.ProviderSaveResult
import dev.lusalhi.lumynoid.core.TaskEvent
import dev.lusalhi.lumynoid.core.TaskRequest
import dev.lusalhi.lumynoid.core.ToolArtifact
import dev.lusalhi.lumynoid.core.ToolOutcome
import java.io.File
import java.util.Base64
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectDeletionTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext.applicationContext as LumynoidApp
    private val automation = instrumentation.uiAutomation
    private val graph get() = app.graph

    @Test
    fun androidResumesDeletionAndKeepsOtherProjectAndSharedStorage() {
        runBlocking {
            if (Build.VERSION.SDK_INT >= 33) {
                automation.grantRuntimePermission(app.packageName, android.Manifest.permission.POST_NOTIFICATIONS)
            }
            if (graph.providerAdmin.overview().active == null) {
                assertEquals(ProviderSaveResult.SAVED, graph.providerAdmin.save(ProviderDraft(
                    id = null, presetId = null, name = "Deletion fixture", baseUrl = "https://fixture.invalid/v1",
                    apiKey = "local-fixture", model = "deletion-fixture", makeActive = true,
                )))
            }
            val providers = graph.data.providerConfigs.providers()
            val activeModel = graph.data.providerConfigs.active()
            val suffix = UUID.randomUUID().toString().take(6)
            val target = seed("! Delete $suffix")
            val other = seed("! Keep $suffix")
            val shared = app.filesDir.resolve("userland/deletion-$suffix.marker").apply {
                parentFile!!.mkdirs()
                writeText("shared runtime storage")
            }
            val (initialActivity, model) = openProjects(target.artifact.conversationId)
            var activity = initialActivity
            try {
                openConfirmation(target.project.name)
                tap(app.getString(R.string.conversation_cancel))
                assertFalse(graph.data.projects.observeAll().first().single { it.id == target.project.id }.deleting)
                // Deny this one directory only: walkFileTree fails before dropping the owner row.
                Os.chmod(target.blobs.path, 0)
                try {
                    openConfirmation(target.project.name)
                    tap(app.getString(R.string.project_delete))
                    withTimeout(12_000) { graph.data.projects.observeAll().first { rows ->
                        rows.any { it.id == target.project.id && it.deleting }
                    } }
                    await(app.getString(R.string.project_resume_deletion, target.project.name), enabled = true)
                    withTimeout(12_000) { model.state.first { it.conversationId == null } }
                    assertNotNull(graph.data.event(target.artifact.conversationId, target.artifact.resultEventId))
                    assertTrue(runCatching {
                        graph.runner.submit(TaskRequest(Origin.User, target.artifact.conversationId, "Blocked task"))
                    }.exceptionOrNull() is IllegalStateException)
                    assertTrue(runCatching {
                        graph.data.conversations.create("Blocked chat", target.project.id)
                    }.exceptionOrNull() is IllegalStateException)
                    assertNotNull(graph.previewArtifact(other.artifact.conversationId, other.artifact.resultEventId, 0))
                    app.startActivity(ArtifactHtmlActivity.intent(app, target.artifact, 0).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    await(app.getString(R.string.preview_unavailable))
                    tap("Close")
                } finally {
                    if (target.blobs.exists()) Os.chmod(target.blobs.path, 0x1c0) // 0700
                }

                instrumentation.runOnMainSync { activity.finish() }
                activity = openProjects().first
                await(target.project.name)
                tap(app.getString(R.string.project_resume_deletion, target.project.name))
                withTimeout(12_000) { graph.data.projects.observeAll().first { rows -> rows.none { it.id == target.project.id } } }
                await(other.project.name)
                assertFalse(target.files.exists())
                assertFalse(target.packages.exists())
                assertFalse(target.blobs.exists())
                assertTrue(graph.data.events.observe(target.artifact.conversationId).first().isEmpty())
                assertEquals(other.project.name, other.files.resolve("index.html").readText())
                assertEquals("installed fixture", other.packages.resolve("fixture").readText())
                assertTrue(other.blobs.isDirectory)
                assertNotNull(graph.previewArtifact(other.artifact.conversationId, other.artifact.resultEventId, 0))
                assertEquals("shared runtime storage", shared.readText())
                assertEquals(providers, graph.data.providerConfigs.providers())
                assertEquals(activeModel, graph.data.providerConfigs.active())
            } finally {
                instrumentation.runOnMainSync { activity.finish() }
                graph.data.projects.delete(target.project.id)
                graph.data.projects.delete(other.project.id)
                shared.delete()
            }
        }
    }

    private class Fixture(
        val project: Project,
        val artifact: ArtifactIdentity,
        val files: File,
        val packages: File,
        val blobs: File,
    )

    private suspend fun seed(name: String): Fixture {
        val data = graph.data
        val project = checkNotNull(data.projects.create(name))
        val chat = data.conversations.create("$name chat", project.id)
        val workspace = app.filesDir.resolve("workspaces/${project.workspaceId}").apply {
            mkdirs()
            resolve("index.html").writeText(name)
        }
        val packages = app.filesDir.resolve("coding-installs/${project.workspaceId}").apply {
            mkdirs()
            resolve("fixture").writeText("installed fixture")
        }
        val encodedChat = Base64.getUrlEncoder().withoutPadding().encodeToString(chat.id.encodeToByteArray())
        val blobs = app.filesDir.resolve("event-blobs/$encodedChat").apply {
            mkdirs()
            resolve("fixture").writeText("stored output fixture")
        }
        val artifact = ArtifactIdentity(chat.id, UUID.randomUUID().toString(), "index.html", "text/html")
        val at = System.currentTimeMillis()
        val taskId = UUID.randomUUID().toString()
        data.events.createTask(taskId, TaskRequest(Origin.User, chat.id, "Create fixture"), at, project.workspaceId)
        data.events.append(chat.id, TaskEvent.ToolResult(artifact.resultEventId, taskId, at, "publish", ToolOutcome(
            "Published", artifacts = listOf(ToolArtifact(artifact.path, artifact.mediaType)), succeeded = true,
        )))
        data.events.append(chat.id, TaskEvent.TaskFinished(UUID.randomUUID().toString(), taskId, at + 1))
        return Fixture(project, artifact, workspace, packages, blobs)
    }

    private suspend fun openProjects(chatId: String? = null): Pair<MainActivity, ChatViewModel> {
        val activity = instrumentation.startActivitySync(
            Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        ) as MainActivity
        lateinit var model: ChatViewModel
        instrumentation.runOnMainSync { model = ViewModelProvider(activity)[ChatViewModel::class.java] }
        withTimeout(12_000) { model.state.first { it.screen == Screen.CHAT && it.pendingConversationIds != null } }
        instrumentation.runOnMainSync {
            if (chatId != null) model.openConversation(chatId)
            model.openProjects()
        }
        await(app.getString(R.string.projects_title))
        return activity to model
    }

    private fun openConfirmation(name: String) {
        tap(app.getString(R.string.project_more_actions, name))
        tap(app.getString(R.string.project_delete))
        await(app.getString(R.string.project_delete_title, name))
        val details = await(app.getString(R.string.project_delete_body)).text.toString().lowercase()
        for (word in listOf("conversations", "logs", "instructions", "files", "packages")) {
            assertTrue("Missing $word in $details", word in details)
        }
    }

    private fun tap(label: String) {
        var node: AccessibilityNodeInfo? = await(label)
        while (node != null && !node.isClickable) node = node.parent
        assertTrue("Could not tap $label", node?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true)
    }

    private fun await(label: String, enabled: Boolean = false): AccessibilityNodeInfo {
        val deadline = SystemClock.uptimeMillis() + 12_000
        val visible = mutableListOf<String>()
        fun find(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (node.isVisibleToUser) {
                for (text in listOfNotNull(node.text, node.contentDescription).map(CharSequence::toString)) {
                    visible += text
                    if (label == text && (!enabled || node.isEnabled)) return node
                }
            }
            for (index in 0 until node.childCount) node.getChild(index)?.let(::find)?.let { return it }
            return null
        }
        do {
            visible.clear()
            automation.rootInActiveWindow?.let(::find)?.let { return it }
            SystemClock.sleep(100)
        } while (SystemClock.uptimeMillis() < deadline)
        throw AssertionError("Not visible: $label. Visible labels: $visible")
    }
}
