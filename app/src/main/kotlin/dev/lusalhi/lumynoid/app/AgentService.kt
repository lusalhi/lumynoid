package dev.lusalhi.lumynoid.app

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import dev.lusalhi.lumynoid.core.TaskRunner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * Hosts the [TaskRunner] so that answers keep streaming when the screen goes away (AD-2).
 * It is a plain started + bound service for now; promotion to a foreground service with a
 * notification belongs to story 8.
 */
class AgentService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var runner: TaskRunner

    inner class LocalBinder : Binder() {
        val runner: TaskRunner get() = this@AgentService.runner
    }

    override fun onCreate() {
        super.onCreate()
        val graph = (application as LumynoidApp).graph
        runner = TaskRunner(graph.llm, graph.data.events, scope)
    }

    override fun onBind(intent: Intent): IBinder = LocalBinder()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_NOT_STICKY

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
