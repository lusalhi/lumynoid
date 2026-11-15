package dev.lusalhi.lumynoid.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/** The answer currently streaming. Deltas are hot state, never persisted (AD-4). */
data class LiveAnswer(val taskId: String, val conversationId: String, val text: String)

/**
 * The single execution path (AD-2): every LLM turn enters through [submit], runs one at a time in
 * FIFO order (AD-3), and leaves its trace as events that only this class appends (AD-4).
 */
class TaskRunner(
    private val llm: LlmPort,
    private val events: EventRepository,
    scope: CoroutineScope,
    private val clock: Clock = SystemClock,
    private val ids: IdGenerator = UuidV7,
) {
    private class Queued(val taskId: String, val request: TaskRequest)

    private val queue = Channel<Queued>(Channel.UNLIMITED)
    private val cancelled = ConcurrentHashMap.newKeySet<String>()
    private val running = MutableStateFlow<Pair<String, Job>?>(null)
    private val _live = MutableStateFlow<LiveAnswer?>(null)

    val live: StateFlow<LiveAnswer?> = _live.asStateFlow()

    init {
        scope.launch {
            for (item in queue) {
                // ATOMIC: the body always starts, so a cancel that lands before it runs still reaches
                // execute's own handler and records TaskCancelled.
                val job = launch(start = CoroutineStart.ATOMIC) { execute(item) }
                running.value = item.taskId to job
                // cancel() adds the id first and reads `running` second; this registers first and reads
                // the id second, so one side always sees the other and no cancel is lost.
                if (cancelled.remove(item.taskId)) job.cancel()
                job.join()
                running.value = null
                cancelled.remove(item.taskId)
            }
        }
    }

    /** Records the task and its user message, queues it, and returns the task id. */
    suspend fun submit(request: TaskRequest): String {
        val taskId = ids.next()
        val now = clock.nowMillis()
        events.createTask(taskId, request, now)
        val item = Queued(taskId, request)
        append(item, TaskEvent.TaskQueued(ids.next(), taskId, now))
        append(item, TaskEvent.UserMessage(ids.next(), taskId, now, request.origin, request.input))
        queue.send(item)
        return taskId
    }

    /** The only cancellation path (AD-22). */
    fun cancel(taskId: String) {
        cancelled.add(taskId)
        running.value?.takeIf { it.first == taskId }?.second?.cancel()
    }

    private suspend fun execute(item: Queued) {
        val text = StringBuilder()
        try {
            currentCoroutineContext().ensureActive()
            var failure: FailureReason? = null
            val request = LlmRequest(listOf(LlmMessage(Role.USER, item.request.input)))
            llm.stream(request).collect { chunk ->
                when (chunk) {
                    is LlmChunk.TextDelta -> {
                        text.append(chunk.text)
                        _live.value = LiveAnswer(item.taskId, item.request.conversationId, text.toString())
                    }
                    is LlmChunk.Failed -> failure = chunk.error.toReason()
                    is LlmChunk.Routed, is LlmChunk.Usage, LlmChunk.Done -> Unit
                }
            }
            if (failure == null && text.isEmpty()) failure = FailureReason.SERVER
            if (failure != null) {
                append(item, TaskEvent.TaskFailed(ids.next(), item.taskId, clock.nowMillis(), failure!!))
            } else {
                append(item, TaskEvent.AssistantMessage(ids.next(), item.taskId, clock.nowMillis(), text.toString()))
                append(item, TaskEvent.TaskFinished(ids.next(), item.taskId, clock.nowMillis()))
            }
        } catch (e: CancellationException) {
            withContext(NonCancellable) {
                append(item, TaskEvent.TaskCancelled(ids.next(), item.taskId, clock.nowMillis()))
            }
            throw e
        } catch (e: Exception) {
            // A broken adapter or a failed write must end this task, not the queue or the process.
            runCatching {
                withContext(NonCancellable) {
                    append(item, TaskEvent.TaskFailed(ids.next(), item.taskId, clock.nowMillis(), FailureReason.SERVER))
                }
            }
        } finally {
            _live.value = null
        }
    }

    private suspend fun append(item: Queued, event: TaskEvent) =
        events.append(item.request.conversationId, event)

    private fun LlmError.toReason(): FailureReason = when (this) {
        is LlmError.AuthFailed -> FailureReason.AUTH_FAILED
        is LlmError.RateLimited -> FailureReason.RATE_LIMITED
        is LlmError.QuotaExhausted -> FailureReason.QUOTA_EXHAUSTED
        is LlmError.Network -> FailureReason.NETWORK
        is LlmError.ToolsUnsupported, is LlmError.MalformedToolCall, is LlmError.Server -> FailureReason.SERVER
    }
}
