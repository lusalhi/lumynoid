package dev.lusalhi.lumynoid.app

import android.app.Application
import android.os.Build
import android.os.SystemClock
import android.webkit.WebView
import dev.lusalhi.lumynoid.core.LlmPort
import dev.lusalhi.lumynoid.core.ProviderAdminPort
import dev.lusalhi.lumynoid.core.TaskRunner
import dev.lusalhi.lumynoid.core.ToolRegistry
import dev.lusalhi.lumynoid.core.ToolSessionRelease
import dev.lusalhi.lumynoid.data.LumynoidData
import dev.lusalhi.lumynoid.llm.LlmRouter
import dev.lusalhi.lumynoid.llm.ProviderAdapters
import dev.lusalhi.lumynoid.llm.ProviderAdmin
import dev.lusalhi.lumynoid.userland.UserlandRuntime
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** Composition root (AD-1): the only place adapters are assembled, by hand and without a DI framework. */
class AppGraph(application: Application) {
    val data: LumynoidData = LumynoidData(
        application,
        deleteWhenIdle = { id, delete -> runner.deleteWhenIdle(id, delete) },
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
        ).also { approvalNotifications.observe(it, agentScope) }
    }
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
