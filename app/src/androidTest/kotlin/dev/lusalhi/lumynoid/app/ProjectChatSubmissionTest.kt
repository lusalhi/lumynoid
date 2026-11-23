package dev.lusalhi.lumynoid.app

import android.content.Intent
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import dev.lusalhi.lumynoid.core.ProviderDraft
import dev.lusalhi.lumynoid.core.ProviderSaveResult
import dev.lusalhi.lumynoid.core.TaskEvent
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Test

class ProjectChatSubmissionTest {
    @Test
    fun twoSendsFromAnUnsavedProjectChatEndUpInTheSameConversation() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as LumynoidApp
        val graph = app.graph
        val fixtureUrl = "https://fixture.invalid/v1"
        if (graph.providerAdmin.overview().active == null) {
            assertEquals(ProviderSaveResult.SAVED, graph.providerAdmin.save(ProviderDraft(
                id = null, presetId = null, name = "Projects fixture", baseUrl = fixtureUrl,
                apiKey = "local-fixture", model = "project-fixture", makeActive = true,
            )))
        }
        val active = checkNotNull(graph.data.providerConfigs.active())
        check(graph.data.providerConfigs.provider(active.providerId)?.baseUrl == fixtureUrl) {
            "Run on a disposable debug install, never with a real provider."
        }
        val project = checkNotNull(graph.data.projects.create("Queued project sends"))
        var activity: MainActivity? = null
        try {
            val opened = instrumentation.startActivitySync(
                Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            ) as MainActivity
            activity = opened
            lateinit var model: ChatViewModel
            instrumentation.runOnMainSync { model = ViewModelProvider(opened)[ChatViewModel::class.java] }
            withTimeout(15_000) { model.state.first { it.screen == Screen.CHAT && it.providerConfigured } }
            instrumentation.runOnMainSync {
                model.newProjectConversation(project.id)
                // Room cannot resume the first call on the main thread between these two sends.
                model.send("First draft message")
                model.send("Second draft message")
            }
            withTimeout(15_000) {
                val chat = graph.data.conversations.observeAll()
                    .map { rows -> rows.filter { it.projectId == project.id } }
                    .first { it.isNotEmpty() }.single()
                val inputs = graph.data.events.observe(chat.id)
                    .map { events -> events.filterIsInstance<TaskEvent.UserMessage>().map { it.text } }
                    .first { it.size >= 2 }
                assertEquals(listOf("First draft message", "Second draft message"), inputs)
                assertEquals(chat.id, graph.data.conversations.observeAll().first().single { it.projectId == project.id }.id)
            }
        } finally {
            try {
                activity?.let { opened ->
                    instrumentation.runOnMainSync { opened.finish() }
                }
                instrumentation.waitForIdleSync()
                withTimeoutOrNull(15_000) {
                    graph.runner.pendingConversations.first { pending ->
                        pending != null && graph.data.conversations.observeAll().first()
                            .filter { it.projectId == project.id }
                            .none { it.id in pending }
                    }
                }
            } finally {
                check(graph.data.projects.delete(project.id)) { "Could not delete the project chat fixture." }
            }
        }
    }
}
