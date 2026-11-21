package dev.lusalhi.lumynoid.app

import dev.lusalhi.lumynoid.core.ApprovalResolution
import dev.lusalhi.lumynoid.core.FailureReason
import dev.lusalhi.lumynoid.core.TaskEvent
import dev.lusalhi.lumynoid.core.ToolCall
import dev.lusalhi.lumynoid.core.ToolOutcome
import dev.lusalhi.lumynoid.core.UiText

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
                // Preflight-denied batches are audit-only: chat keeps its blocked error, not result-less cards.
                if (event.deniedCallIds.isNotEmpty() && event.assistantText.isEmpty()) continue
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

/** Why a requested call never produced a result of its own. */
internal enum class CallDenial { PREFLIGHT, EXECUTION, BATCH_BLOCKED }

/** Factual outcome of one requested call, derived only from recorded events. */
internal enum class ActionStatus {
    SUCCEEDED, FAILED, UNCONFIRMED,
    DENIED_PREFLIGHT, DENIED_EXECUTION, NOT_RUN_BATCH,
    APPROVAL_DENIED, APPROVAL_EXPIRED, APPROVAL_CANCELLED, AWAITING_APPROVAL,
    INTERRUPTED, CANCELLED, NO_RESULT, PENDING,
}

internal sealed interface ActionLogRow {
    val key: String

    data class Action(
        override val key: String,
        val call: TaskEvent.RequestedToolCall,
        val outcome: ToolOutcome? = null,
        val resultEventId: String? = null,
        val denial: CallDenial? = null,
        val approvalId: String? = null,
        val approvalResolution: ApprovalResolution? = null,
        val taskEnd: TaskEvent? = null,
    ) : ActionLogRow {
        /** A stored result speaks first; missing evidence never becomes success. */
        fun status(): ActionStatus = when {
            outcome != null -> when {
                outcome.succeeded == true -> ActionStatus.SUCCEEDED
                outcome.succeeded == false -> ActionStatus.FAILED
                // A recorded nonzero exit is a definitive failure even without a typed verdict.
                outcome.exitCode != null && outcome.exitCode != 0 -> ActionStatus.FAILED
                else -> ActionStatus.UNCONFIRMED
            }
            denial == CallDenial.PREFLIGHT -> ActionStatus.DENIED_PREFLIGHT
            denial == CallDenial.EXECUTION -> ActionStatus.DENIED_EXECUTION
            denial == CallDenial.BATCH_BLOCKED -> ActionStatus.NOT_RUN_BATCH
            approvalResolution == ApprovalResolution.DENIED -> ActionStatus.APPROVAL_DENIED
            approvalResolution == ApprovalResolution.EXPIRED -> ActionStatus.APPROVAL_EXPIRED
            approvalResolution == ApprovalResolution.CANCELLED -> ActionStatus.APPROVAL_CANCELLED
            taskEnd is TaskEvent.TaskCancelled -> ActionStatus.CANCELLED
            taskEnd is TaskEvent.TaskFailed ->
                if (taskEnd.reason == FailureReason.INTERRUPTED) ActionStatus.INTERRUPTED else ActionStatus.NO_RESULT
            taskEnd is TaskEvent.TaskFinished -> ActionStatus.NO_RESULT
            approvalId != null && approvalResolution == null -> ActionStatus.AWAITING_APPROVAL
            else -> ActionStatus.PENDING
        }

        /** Stable identities of published files: the stored result record plus conversation-relative paths. */
        fun artifactIdentities(conversationId: String): List<ArtifactIdentity> {
            val resultId = resultEventId ?: return emptyList()
            return outcome?.artifacts?.map { ArtifactIdentity(conversationId, resultId, it.path, it.mediaType) }.orEmpty()
        }
    }

    data class Notice(val event: TaskEvent, override val key: String) : ActionLogRow
}

/** A published file's stable identity, kept typed for later stories; the log itself shows path and media type. */
internal data class ArtifactIdentity(val conversationId: String, val resultEventId: String, val path: String, val mediaType: String)

/** The entry summary's target: the recorded result target, else the request's own subject. */
internal fun actionTarget(call: TaskEvent.RequestedToolCall, outcome: ToolOutcome?): String? =
    outcome?.target
        ?: call.description.args["path"]?.content
        ?: call.description.args["input"]?.content
        ?: call.description.args["output"]?.content
        ?: call.description.args["package"]?.content
        ?: call.description.args["operation"]?.content
        ?: call.description.args["version"]?.content
        ?: call.description.args["source"]?.content

/**
 * The read-only action log: every requested call keeps its request position, joined to its result,
 * denial, approval decision and task terminal event by task/call in event order. Nothing is inferred
 * from absent evidence, and no row carries an action the user could take on history.
 */
internal fun actionLogRows(events: List<TaskEvent>): List<ActionLogRow> = buildList {
    val actions = mutableMapOf<Pair<String, String>, Int>()
    val taskActions = mutableMapOf<String, MutableList<Int>>()
    fun actionIndex(taskId: String, callId: String) = actions[taskId to callId]
    for (event in events) {
        when (event) {
            is TaskEvent.ToolCallRequested -> {
                for (call in event.calls) {
                    // Preflight denies the whole batch before any call runs; only the assessed call is the denied one.
                    val denial = when {
                        call.call.id in event.deniedCallIds -> CallDenial.PREFLIGHT
                        event.deniedCallIds.isNotEmpty() -> CallDenial.BATCH_BLOCKED
                        else -> null
                    }
                    actions[event.taskId to call.call.id] = size
                    taskActions.getOrPut(event.taskId) { mutableListOf() } += size
                    add(
                        ActionLogRow.Action(
                            key = "${event.id}:${call.call.id}",
                            call = call,
                            denial = denial,
                        ),
                    )
                }
            }
            is TaskEvent.ToolResult -> {
                val index = actionIndex(event.taskId, event.callId)
                if (index != null) {
                    set(index, (get(index) as ActionLogRow.Action).copy(outcome = event.outcome, resultEventId = event.id))
                } else {
                    // The request record is missing; keep the real result evidence as an unknown call.
                    actions[event.taskId to event.callId] = size
                    taskActions.getOrPut(event.taskId) { mutableListOf() } += size
                    add(
                        ActionLogRow.Action(
                            key = "${event.id}:${event.callId}",
                            call = TaskEvent.RequestedToolCall(ToolCall(event.callId, "", "{}"), UiText("")),
                            outcome = event.outcome,
                            resultEventId = event.id,
                        ),
                    )
                }
            }
            is TaskEvent.ToolExecutionDenied -> {
                val index = actionIndex(event.taskId, event.callId) ?: continue
                val row = get(index) as ActionLogRow.Action
                if (row.outcome == null && row.denial == null) set(index, row.copy(denial = CallDenial.EXECUTION))
            }
            is TaskEvent.ApprovalRequested -> {
                val callId = event.approval.call.call.id
                val index = actionIndex(event.taskId, callId)
                if (index != null) {
                    set(index, (get(index) as ActionLogRow.Action).copy(approvalId = event.approval.id))
                } else {
                    // A record without its request still belongs in the log, at the position it was stored.
                    actions[event.taskId to callId] = size
                    taskActions.getOrPut(event.taskId) { mutableListOf() } += size
                    add(
                        ActionLogRow.Action(
                            key = "${event.id}:$callId",
                            call = event.approval.call,
                            approvalId = event.approval.id,
                        ),
                    )
                }
            }
            is TaskEvent.ApprovalResolved -> {
                val index = actionIndex(event.taskId, event.callId) ?: continue
                val row = get(index) as ActionLogRow.Action
                if (row.approvalId != event.approvalId) continue
                set(index, row.copy(approvalResolution = event.decision))
            }
            is TaskEvent.TaskFinished, is TaskEvent.TaskFailed, is TaskEvent.TaskCancelled -> {
                for (index in taskActions[event.taskId].orEmpty()) {
                    set(index, (get(index) as ActionLogRow.Action).copy(taskEnd = event))
                }
                add(ActionLogRow.Notice(event, event.id))
            }
            is TaskEvent.ToolCallRejected, is TaskEvent.LlmRetry -> add(ActionLogRow.Notice(event, event.id))
            is TaskEvent.Unknown -> add(ActionLogRow.Notice(event, "unknown-$size"))
            else -> Unit
        }
    }
}

/**
 * Splits stored output into bounded pieces for lazy composition. Pieces concatenate back to the exact
 * input, never exceed [maxChars] UTF-16 units, and never cut a surrogate pair, so the final character
 * of even a very large result stays reachable. Only chunk offsets are stored up front; text is
 * allocated per chunk when it is actually read.
 */
internal fun outputChunks(text: String, maxChars: Int = OUTPUT_CHUNK_CHARS): List<String> {
    require(maxChars >= 2) { "A chunk must fit at least one surrogate pair" }
    if (text.isEmpty()) return emptyList()
    var count = 0
    var start = 0
    while (start < text.length) {
        start = chunkEnd(text, start, maxChars)
        count++
    }
    val ends = IntArray(count)
    start = 0
    for (index in 0 until count) {
        start = chunkEnd(text, start, maxChars)
        ends[index] = start
    }
    return ChunkedOutput(text, ends)
}

private fun chunkEnd(text: String, start: Int, maxChars: Int): Int {
    val end = start + minOf(maxChars, text.length - start)
    return if (end < text.length && text[end - 1].isHighSurrogate()) end - 1 else end
}

/** A lazy view over one stored output: memory scales with the chunk count, not the output size. */
private class ChunkedOutput(private val text: String, private val ends: IntArray) : AbstractList<String>() {
    override val size: Int get() = ends.size

    override fun get(index: Int): String {
        if (index < 0 || index >= ends.size) throw IndexOutOfBoundsException("index $index, size ${ends.size}")
        val start = if (index == 0) 0 else ends[index - 1]
        return text.substring(start, ends[index])
    }
}

internal const val OUTPUT_CHUNK_CHARS = 4_096
