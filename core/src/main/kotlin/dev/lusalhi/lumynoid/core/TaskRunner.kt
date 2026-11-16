package dev.lusalhi.lumynoid.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
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

/** The task the runner is executing right now, from the moment its job starts until it has ended. */
data class ActiveTask(val taskId: String, val conversationId: String)

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
    private val _active = MutableStateFlow<ActiveTask?>(null)
    private val _busy = MutableStateFlow(false)
    private val pendingLock = Any()
    private var pending = 0

    /** Completed once crash recovery has closed the tasks of the previous run; submit waits for it. */
    private val recovered = CompletableDeferred<Unit>()

    val live: StateFlow<LiveAnswer?> = _live.asStateFlow()

    /** The task being executed, set before its first token and cleared after it ended. */
    val active: StateFlow<ActiveTask?> = _active.asStateFlow()

    /** True from [submit] until the last queued or running task has ended, with no gap between tasks. */
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    init {
        scope.launch {
            try {
                recoverInterrupted()
            } finally {
                recovered.complete(Unit)
            }
            for (item in queue) {
                // ATOMIC: the body always starts, so a cancel that lands before it runs still reaches
                // execute's own handler and records TaskCancelled.
                val job = launch(start = CoroutineStart.ATOMIC) { execute(item) }
                running.value = item.taskId to job
                _active.value = ActiveTask(item.taskId, item.request.conversationId)
                // cancel() adds the id first and reads `running` second; this registers first and reads
                // the id second, so one side always sees the other and no cancel is lost.
                if (cancelled.remove(item.taskId)) job.cancel()
                job.join()
                running.value = null
                _active.value = null
                cancelled.remove(item.taskId)
                adjustPending(-1)
            }
        }
    }

    /** AD-22: tasks left without a terminal event by a crash or kill are closed before new work starts. */
    private suspend fun recoverInterrupted() {
        val tasks = try {
            events.unfinishedTasks()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // A failed recovery must not keep the queue from running.
            return
        }
        for (task in tasks) {
            try {
                events.append(
                    task.conversationId,
                    TaskEvent.TaskFailed(ids.next(), task.taskId, clock.nowMillis(), FailureReason.INTERRUPTED),
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Keep closing the other tasks.
            }
        }
    }

    private fun adjustPending(delta: Int) = synchronized(pendingLock) {
        pending += delta
        _busy.value = pending > 0
    }

    /** Records the task and its user message, queues it, and returns the task id. */
    suspend fun submit(request: TaskRequest): String {
        recovered.await()
        val taskId = ids.next()
        val now = clock.nowMillis()
        adjustPending(1)
        try {
            events.createTask(taskId, request, now)
            val item = Queued(taskId, request)
            append(item, TaskEvent.TaskQueued(ids.next(), taskId, now))
            append(item, TaskEvent.UserMessage(ids.next(), taskId, now, request.origin, request.input))
            queue.send(item)
        } catch (e: Throwable) {
            adjustPending(-1)
            throw e
        }
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
            val request = ContextAssembler.assemble(events.history(item.request.conversationId), item.taskId)
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
