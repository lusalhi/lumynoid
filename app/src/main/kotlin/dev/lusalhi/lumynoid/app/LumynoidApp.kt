package dev.lusalhi.lumynoid.app

import android.app.Application
import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.webkit.WebView
import dev.lusalhi.lumynoid.core.ConversationWorkspace
import dev.lusalhi.lumynoid.core.LlmPort
import dev.lusalhi.lumynoid.core.ProviderAdminPort
import dev.lusalhi.lumynoid.core.TaskEvent
import dev.lusalhi.lumynoid.core.TaskRunner
import dev.lusalhi.lumynoid.core.ToolRegistry
import dev.lusalhi.lumynoid.core.ToolSessionRelease
import dev.lusalhi.lumynoid.data.LumynoidData
import dev.lusalhi.lumynoid.llm.LlmRouter
import dev.lusalhi.lumynoid.llm.ProviderAdapters
import dev.lusalhi.lumynoid.llm.ProviderAdmin
import dev.lusalhi.lumynoid.userland.UserlandRuntime
import dev.lusalhi.lumynoid.userland.WorkspacePreview
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Composition root (AD-1): the only place adapters are assembled, by hand and without a DI framework. */
class AppGraph(private val application: Application) {
    val data: LumynoidData = LumynoidData(
        application,
        deleteWhenIdle = { id, delete -> runner.deleteWhenIdle(id, delete) },
        withLifecycle = { action -> runner.withConversationLifecycle(action) },
        deleteWorkspace = { id -> UserlandRuntime.deleteWorkspace(application, id) },
    )

    private val httpClient = HttpClient(OkHttp) {
        install(HttpTimeout) {
            connectTimeoutMillis = 15_000
            socketTimeoutMillis = 60_000
            // No overall request timeout: an answer streams for as long as the provider keeps sending.
        }
    }

    private val adapters = ProviderAdapters(httpClient)

    val llm: LlmPort = LlmRouter(adapters, data.providerConfigs, data.secrets)
    val providerAdmin: ProviderAdminPort = ProviderAdmin(adapters, data.providerConfigs, data.secrets)

    val userland by lazy { UserlandRuntime.create(application, httpClient) }
    private val agentScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    internal val approvalNotifications = ApprovalNotifications(application)
    val runner: TaskRunner by lazy {
        TaskRunner(
            llm, data.events, agentScope,
            tools = ToolRegistry(listOfNotNull(userland)),
            toolSessions = userland ?: ToolSessionRelease {},
            elapsedMillis = SystemClock::elapsedRealtime,
            maxLlmCalls = data.taskSettings::maxLlmCalls,
            resolveWorkspace = data.conversations::workspace,
            instructionsForWorkspace = data.projects::instructionsForWorkspace,
        ).also { approvalNotifications.observe(it, agentScope) }
    }

    internal fun adoptLegacyWorkspaces() {
        if (Application.getProcessName() != application.packageName) return
        agentScope.launch {
            try {
                data.adoptLegacyWorkspaces(application.getString(R.string.project_untitled)) { id ->
                    UserlandRuntime.legacyWorkspaceHasData(application, id)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("LegacyWorkspaceAdoption", "Adoption paused; will retry when the app starts again", e)
            }
        }
    }

    internal suspend fun previewArtifact(conversationId: String, resultId: String, index: Int): PublishedArtifact? =
        resolvePublishedArtifact(data, application, conversationId, resultId, index)
}

internal data class PublishedArtifact(val artifact: ArtifactIdentity, val workspace: WorkspacePreview)

/**
 * The production artifact resolver: a persisted [TaskEvent.ToolResult] plus the conversation's resolved
 * project scope. Extracted unchanged so the diagnostic fixture validates targets with the same rules.
 * A missing event, a non-project scope (plain, pending legacy or deleting project) or a bad index yields null.
 */
internal suspend fun resolvePublishedArtifact(
    data: LumynoidData,
    context: Context,
    conversationId: String,
    resultId: String,
    index: Int,
): PublishedArtifact? {
    val result = data.event(conversationId, resultId) as? TaskEvent.ToolResult ?: return null
    val file = result.outcome.artifacts.getOrNull(index) ?: return null
    val scope = data.conversations.workspace(conversationId) as? ConversationWorkspace.Project ?: return null
    return PublishedArtifact(
        ArtifactIdentity(conversationId, resultId, file.path, file.mediaType),
        WorkspacePreview(context, scope.workspaceId),
    )
}

class LumynoidApp : Application() {
    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= 30 && getProcessName() == "$packageName:preview") {
            WebView.setDataDirectorySuffix("preview")
        }
        // First, before the lazy graph: a crash while the graph builds must still be recorded (AD-25).
        CrashHandler.install(this)
    }

    val graph: AppGraph by lazy { AppGraph(this) }
}
