package dev.lusalhi.lumynoid.app

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.core.app.NotificationCompat
import dev.lusalhi.lumynoid.core.PendingApproval
import dev.lusalhi.lumynoid.core.TaskRunner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

internal data class ApprovalTarget(val taskId: String, val eventId: String) {
    fun matches(approval: PendingApproval) = approval.event.taskId == taskId && approval.event.id == eventId
}

/** Lives with the runner, not the foreground service. The private entry point hands off only in-process. */
internal class ApprovalNotifications(private val context: Context) {
    private val manager = checkNotNull(context.getSystemService(NotificationManager::class.java))
    private val notificationsAllowed = MutableStateFlow(false)
    var openedTarget: ApprovalTarget? = null

    fun refreshPermission() {
        notificationsAllowed.value = manager.areNotificationsEnabled() &&
            manager.getNotificationChannel(CHANNEL)?.importance != NotificationManager.IMPORTANCE_NONE
    }

    fun observe(runner: TaskRunner, scope: CoroutineScope) {
        scope.launch {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL, context.getString(R.string.notification_channel_approval),
                    NotificationManager.IMPORTANCE_DEFAULT),
            )
            val posted = manager.activeNotifications.filter { it.id == ID }.mapNotNull { it.tag }.toMutableSet()
            refreshPermission()
            combine(runner.pendingApprovals, notificationsAllowed, ::Pair).collect { (pending, allowed) ->
                val tags = posted.iterator()
                while (tags.hasNext()) {
                    val tag = tags.next()
                    if (!allowed || tag !in pending) {
                        manager.cancel(tag, ID)
                        tags.remove()
                    }
                }
                if (!allowed) return@collect
                for ((tag, approval) in pending) {
                    if (tag in posted) continue
                    try {
                        manager.notify(tag, ID, notification(approval))
                        posted.add(tag)
                    } catch (e: SecurityException) {
                        Log.w("ApprovalNotifications", "Could not post approval notification", e)
                    }
                }
            }
        }
    }

    private fun notification(approval: PendingApproval): Notification {
        val event = approval.event
        val intent = Intent(context, ApprovalLinkActivity::class.java)
            .setIdentifier(event.id) // PendingIntent identity ignores extras.
            .putExtra(EXTRA_TASK, event.taskId)
            .putExtra(EXTRA_EVENT, event.id)
        val open = PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_task)
            .setContentTitle(context.getString(R.string.notification_approval_title))
            .setContentText(context.getString(toolTitle(event.approval.call.description)))
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setContentIntent(open)
            .build()
    }

    private companion object {
        const val CHANNEL = "task_approval"
        const val ID = 2
    }
}

class ApprovalLinkActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val taskId = intent.getStringExtra(EXTRA_TASK)
        val eventId = intent.getStringExtra(EXTRA_EVENT)
        if (taskId != null && eventId != null) {
            val graph = (application as LumynoidApp).graph
            graph.approvalNotifications.openedTarget = ApprovalTarget(taskId, eventId)
        }
        startActivity(Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        finish()
    }
}

private const val EXTRA_TASK = "task_id"
private const val EXTRA_EVENT = "event_id"
