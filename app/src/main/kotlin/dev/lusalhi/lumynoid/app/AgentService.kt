package dev.lusalhi.lumynoid.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import dev.lusalhi.lumynoid.core.TaskRunner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Hosts the [TaskRunner] so that answers keep streaming when the screen goes away (AD-2).
 *
 * The service is a bound service while idle. Only while the runner is busy (a task queued or
 * running) it also starts itself, goes foreground with the `task_running` notification and holds a
 * partial wake lock; when the runner is idle again it drops all three (C5). Failing to go
 * foreground is logged and never stops the task.
 */
class AgentService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mainScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var runner: TaskRunner
    private var wakeLock: PowerManager.WakeLock? = null
    private var foreground = false

    inner class LocalBinder : Binder() {
        val runner: TaskRunner get() = this@AgentService.runner
    }

    override fun onCreate() {
        super.onCreate()
        val graph = (application as LumynoidApp).graph
        runner = TaskRunner(graph.llm, graph.data.events, scope)
        createChannel()
        mainScope.launch {
            runner.busy.collect { busy -> if (busy) enterForeground() else leaveForeground() }
        }
    }

    override fun onBind(intent: Intent): IBinder = LocalBinder()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_NOT_STICKY

    override fun onDestroy() {
        mainScope.cancel()
        scope.cancel()
        releaseWakeLock()
        super.onDestroy()
    }

    private fun enterForeground() {
        if (foreground) return
        foreground = true
        attempt("start service") { startService(Intent(this, AgentService::class.java)) }
        attempt("start foreground") {
            val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            } else {
                0
            }
            ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(), type)
        }
        attempt("acquire wake lock") {
            val lock = wakeLock ?: (getSystemService(POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "lumynoid:task").apply { setReferenceCounted(false) }
            wakeLock = lock
            lock.acquire(WAKE_LOCK_TIMEOUT_MS)
        }
    }

    private fun leaveForeground() {
        if (!foreground) return
        foreground = false
        attempt("stop foreground") { ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE) }
        releaseWakeLock()
        attempt("stop self") { stopSelf() }
    }

    private fun releaseWakeLock() {
        attempt("release wake lock") { wakeLock?.takeIf { it.isHeld }?.release() }
    }

    private fun attempt(what: String, block: () -> Unit) {
        runCatching(block).onFailure { Log.w(TAG, "Could not $what", it) }
    }

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_task_running),
            NotificationManager.IMPORTANCE_LOW,
        )
        attempt("create notification channel") {
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_task)
            .setContentTitle(getString(R.string.notification_task_running_title))
            .setContentText(getString(R.string.notification_task_running_text))
            .setOngoing(true)
            .setContentIntent(open)
            .build()
    }

    private companion object {
        const val TAG = "AgentService"
        const val CHANNEL_ID = "task_running"
        const val NOTIFICATION_ID = 1
        const val WAKE_LOCK_TIMEOUT_MS = 60 * 60 * 1000L
    }
}
