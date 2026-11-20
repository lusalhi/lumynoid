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
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonObject
import java.util.TreeMap
import java.util.concurrent.ConcurrentHashMap
import java.util.regex.Pattern

// The tree reader accepts arbitrary bare literals. Check lexical tokens before it checks structure.
private val jsonToken = Pattern.compile(
    """[ \t\r\n]++|[{}\[\],:]|"(?:[^"\\\x00-\x1f]++|\\(?:["\\/bfnrt]|u[0-9a-fA-F]{4}))*+"|""" +
        """(?:null|true|false|-?(?:0|[1-9][0-9]*)(?:\.[0-9]+)?(?:[eE][+-]?[0-9]+)?)(?=[ \t\r\n,}\]]|\z)""",
)

private fun hasStrictJsonTokens(text: String): Boolean {
    val matcher = jsonToken.matcher(text)
    var offset = 0
    while (offset < text.length) {
        if (!matcher.region(offset, text.length).lookingAt()) return false
        offset = matcher.end()
    }
    return true
}

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
    private val tools: ToolRegistry = ToolRegistry(),
) {
    private class Queued(val taskId: String, val request: TaskRequest)

    private val queue = Channel<Queued>(Channel.UNLIMITED)
    private val cancelled = ConcurrentHashMap.newKeySet<String>()
    private val running = MutableStateFlow<Pair<String, Job>?>(null)
    private val _live = MutableStateFlow<LiveAnswer?>(null)
    private val _active = MutableStateFlow<ActiveTask?>(null)
    private val _busy = MutableStateFlow(false)
    private val _pendingConversations = MutableStateFlow<Set<String>?>(null)
    private val pendingLock = Any()
    private val pendingCounts = mutableMapOf<String, Int>()

    /** Completed once crash recovery has closed the tasks of the previous run; submit waits for it. */
    private val recovered = CompletableDeferred<Unit>()

    val live: StateFlow<LiveAnswer?> = _live.asStateFlow()

    /** The task being executed, set before its first token and cleared after it ended. */
    val active: StateFlow<ActiveTask?> = _active.asStateFlow()

    /** True from [submit] until the last queued or running task has ended, with no gap between tasks. */
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    /** Conversations with tasks being submitted, queued, or executed; null during crash recovery. */
    val pendingConversations: StateFlow<Set<String>?> = _pendingConversations.asStateFlow()

    init {
        scope.launch {
            try {
                recoverInterrupted()
            } finally {
                _pendingConversations.value = emptySet()
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
                adjustPending(item.request.conversationId, -1)
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

    private fun adjustPending(conversationId: String, delta: Int) = synchronized(pendingLock) {
        val previous = pendingCounts[conversationId] ?: 0
        val count = previous + delta
        if (count == 0) pendingCounts.remove(conversationId) else pendingCounts[conversationId] = count
        if (previous == 0 || count == 0) {
            _pendingConversations.value = pendingCounts.keys.toSet()
            _busy.value = pendingCounts.isNotEmpty()
        }
    }

    /** Records the task and its user message, queues it, and returns the task id. */
    suspend fun submit(request: TaskRequest): String {
        recovered.await()
        val taskId = ids.next()
        val now = clock.nowMillis()
        adjustPending(request.conversationId, 1)
        try {
            events.createTask(taskId, request, now)
            val item = Queued(taskId, request)
            append(item, TaskEvent.TaskQueued(ids.next(), taskId, now))
            append(item, TaskEvent.UserMessage(ids.next(), taskId, now, request.origin, request.input))
            queue.send(item)
        } catch (e: Throwable) {
            adjustPending(request.conversationId, -1)
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
        try {
            currentCoroutineContext().ensureActive()
            val registry = tools.snapshot()
            var request = ContextAssembler.assemble(events.history(item.request.conversationId), item.taskId)
            if (registry.isNotEmpty()) request = request.copy(tools = registry.values.map { it.definition })
            var toolTurn = false
            while (true) {
                val reply = readReply(item, request)
                if (reply.terminal is LlmChunk.Failed) {
                    fail(item, reply.terminal.error.toReason())
                    return
                }
                val fragments = reply.fragments
                if (fragments == null) {
                    if (reply.text.isEmpty() || (toolTurn && reply.terminal != LlmChunk.Done)) {
                        fail(item, FailureReason.SERVER)
                    } else {
                        append(item, TaskEvent.AssistantMessage(ids.next(), item.taskId, clock.nowMillis(), reply.text))
                        append(item, TaskEvent.TaskFinished(ids.next(), item.taskId, clock.nowMillis()))
                    }
                    return
                }
                val calls = if (reply.terminal == LlmChunk.Done) prepareCalls(fragments, registry) else null
                if (calls == null) {
                    fail(item, LlmError.MalformedToolCall.toReason())
                    return
                }
                request = request.copy(messages = runToolRound(item, request.messages, reply.text, calls))
                toolTurn = true
                _live.value = null
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
                    fail(item, FailureReason.SERVER)
                }
            }
        } finally {
            _live.value = null
        }
    }

    private class ToolFragments {
        val name = StringBuilder()
        val arguments = StringBuilder()
    }

    private data class Reply(
        val text: String,
        val fragments: TreeMap<Int, ToolFragments>?,
        val terminal: LlmChunk?,
    )

    private suspend fun readReply(item: Queued, request: LlmRequest): Reply {
        val text = StringBuilder()
        var fragments: TreeMap<Int, ToolFragments>? = null
        val terminal = llm.stream(request).firstOrNull { chunk ->
            when (chunk) {
                is LlmChunk.TextDelta -> {
                    text.append(chunk.text)
                    _live.value = LiveAnswer(item.taskId, item.request.conversationId, text.toString())
                }
                is LlmChunk.ToolCallDelta -> {
                    val calls = fragments ?: TreeMap<Int, ToolFragments>().also { fragments = it }
                    val call = calls.getOrPut(chunk.index, ::ToolFragments)
                    chunk.nameFragment?.let(call.name::append)
                    chunk.argumentsFragment?.let(call.arguments::append)
                }
                is LlmChunk.Failed, LlmChunk.Done -> return@firstOrNull true
                is LlmChunk.Routed, is LlmChunk.Usage -> Unit
            }
            false
        }
        return Reply(text.toString(), fragments, terminal)
    }

    private data class PreparedCall(
        val tool: Tool,
        val args: JsonObject,
        val request: TaskEvent.RequestedToolCall,
    )

    private fun prepareCalls(
        fragments: Map<Int, ToolFragments>,
        registry: Map<String, ToolRegistry.Entry>,
    ): List<PreparedCall>? = buildList(fragments.size) {
        for ((index, fragmentsAtIndex) in fragments) {
            if (index < 0) return null
            val name = fragmentsAtIndex.name.toString()
            val tool = registry[name]?.tool ?: return null
            val argsJson = fragmentsAtIndex.arguments.toString()
            if (!hasStrictJsonTokens(argsJson)) return null
            val args = try {
                TaskEventJson.json.parseToJsonElement(argsJson) as? JsonObject ?: return null
            } catch (_: SerializationException) {
                return null
            }
            // The approval policy is not installed yet. Never dispatch work of uncertain or higher risk.
            if (tool.assess(args) != RiskAssessment.READ_ONLY) return null
            val call = TaskEvent.RequestedToolCall(ToolCall(ids.next(), name, argsJson), tool.describe(args))
            add(PreparedCall(tool, args, call))
        }
    }

    private suspend fun runToolRound(
        item: Queued,
        messages: List<LlmMessage>,
        assistantText: String,
        calls: List<PreparedCall>,
    ): List<LlmMessage> {
        val request = TaskEvent.ToolCallRequested(
            ids.next(), item.taskId, clock.nowMillis(), assistantText, calls.map { it.request },
        )
        append(item, request)
        val context = ToolContext(item.taskId, item.request.conversationId, item.request.workspaceId)
        return buildList(messages.size + calls.size + 1) {
            addAll(messages)
            add(request.toLlmMessage())
            for (call in calls) {
                currentCoroutineContext().ensureActive()
                val outcome = call.tool.execute(call.args, context)
                val callId = call.request.call.id
                val result = TaskEvent.ToolResult(ids.next(), item.taskId, clock.nowMillis(), callId, outcome)
                append(item, result)
                add(result.toLlmMessage())
            }
        }
    }

    private suspend fun fail(item: Queued, reason: FailureReason) =
        append(item, TaskEvent.TaskFailed(ids.next(), item.taskId, clock.nowMillis(), reason))

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
