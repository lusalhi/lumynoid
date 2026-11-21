package dev.lusalhi.lumynoid.app

import dev.lusalhi.lumynoid.core.ApprovalResolution
import dev.lusalhi.lumynoid.core.TaskEvent
import dev.lusalhi.lumynoid.core.ToolOutcome

internal sealed interface ChatRow {
    val key: String

    data class Event(val event: TaskEvent) : ChatRow {
        override val key get() = event.id
    }

    data class Activity(
        override val key: String,
        val call: TaskEvent.RequestedToolCall,
        val outcome: ToolOutcome? = null,
    ) : ChatRow

    data class Approval(
        val event: TaskEvent.ApprovalRequested,
        val resolution: ApprovalResolution? = null,
        val closed: Boolean = false,
    ) : ChatRow {
        override val key get() = event.id

        fun canDecide(elapsedDeadline: Long?, now: Long) =
            !closed && resolution == null && elapsedDeadline != null && now < elapsedDeadline
    }
}

/** Preserve request positions as results arrive. Neither Markdown nor tool output is parsed as UI. */
internal fun chatRows(events: List<TaskEvent>): List<ChatRow> = buildList {
    val callRows = mutableMapOf<Pair<String, String>, Int>()
    val approvalRows = mutableMapOf<String, Int>()
    for (event in events) {
        when (event) {
            is TaskEvent.UserMessage, is TaskEvent.AssistantMessage -> add(ChatRow.Event(event))
            is TaskEvent.TaskFinished, is TaskEvent.TaskFailed, is TaskEvent.TaskCancelled -> {
                approvalRows.remove(event.taskId)?.let { index ->
                    set(index, (get(index) as ChatRow.Approval).copy(closed = true))
                }
                if (event !is TaskEvent.TaskFinished) add(ChatRow.Event(event))
            }
            is TaskEvent.ToolCallRequested -> {
                if (event.assistantText.isNotBlank()) add(ChatRow.Event(event))
                for (call in event.calls) {
                    callRows[event.taskId to call.call.id] = size
                    add(ChatRow.Activity("${event.id}:${call.call.id}", call))
                }
            }
            is TaskEvent.ToolResult -> {
                val index = callRows.remove(event.taskId to event.callId) ?: continue
                set(index, (get(index) as ChatRow.Activity).copy(outcome = event.outcome))
            }
            is TaskEvent.ApprovalRequested -> {
                approvalRows[event.taskId] = size
                add(ChatRow.Approval(event))
            }
            is TaskEvent.ApprovalResolved -> {
                val index = approvalRows[event.taskId] ?: continue
                val row = get(index) as ChatRow.Approval
                if (row.event.approval.id != event.approvalId || row.event.approval.call.call.id != event.callId) continue
                approvalRows.remove(event.taskId)
                set(index, row.copy(resolution = event.decision))
            }
            else -> Unit
        }
    }
}
