package dev.lusalhi.lumynoid.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import java.util.TreeMap

/** The answer currently streaming. Deltas are hot state, never persisted (AD-4). */
data class LiveAnswer(val taskId: String, val conversationId: String, val text: String)

/** The task the runner is executing right now, from the moment its job starts until it has ended. */
data class ActiveTask(val taskId: String, val conversationId: String)

/** A durable request still awaiting a decision in this process. */
data class PendingApproval(
    val conversationId: String,
    val event: TaskEvent.ApprovalRequested,
    /** Monotonic deadline in the same clock domain as the runner's elapsedMillis. */
    val elapsedDeadline: Long,
)

/**
 * The single execution path (AD-2): every LLM turn enters through [submit], runs one at a time in
 * FIFO order with priority for approved continuations (AD-3), and leaves its trace in the event log (AD-4).
 */
class TaskRunner(
    private val llm: LlmPort,
    private val events: EventRepository,
    private val scope: CoroutineScope,
    private val clock: Clock = SystemClock,
    private val ids: IdGenerator = UuidV7,
    private val tools: ToolRegistry = ToolRegistry(),
    private val toolSessions: ToolSessionRelease = ToolSessionRelease {},
    private val elapsedMillis: () -> Long = { System.nanoTime() / 1_000_000L },
    private val maxLlmCalls: suspend () -> Int = { DEFAULT_MAX_LLM_CALLS },
    private val resolveWorkspace: suspend (String) -> ConversationWorkspace?,
    /** Read once per fresh request; repair and approval continuations keep the captured instructions. */
    private val instructionsForWorkspace: suspend (String) -> String? = { null },
) : ApprovalPort {
    private class Task(
        val taskId: String,
        val request: TaskRequest,
        val maxLlmCalls: Int,
        val workspace: ConversationWorkspace,
    ) {
        val context = ToolContext(taskId, request.conversationId, (workspace as? ConversationWorkspace.Project)?.workspaceId)
        var llmCalls = 0
        var repaired = false
        var cancelled = false
        var job: Job? = null
        var waiting: WaitingApproval? = null
    }

    private class Work(val task: Task, val resumed: WaitingApproval? = null)

    private class WaitingApproval(
        val task: Task,
        val approval: ToolApproval,
        val continuation: Continuation,
        val elapsedDeadline: Long,
        val resolutionId: String,
        val requestEventId: String,
    ) {
        var expiryJob: Job? = null
        var resolution: TaskEvent.ApprovalResolved? = null
    }

    // Deletion and submission reserve the same boundary before touching conversation storage.
    private val admissionLock = Mutex()
    private val stateLock = Any()
    private val tasks = mutableMapOf<String, Task>()
    private val queue = ArrayDeque<Work>()
    private val approved = ArrayDeque<Work>()
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private var runnable = 0

    // Hold through the durable write so an observer can resolve a request as soon as it sees the event.
    private val approvalLock = Mutex()
    private val approvals = mutableMapOf<String, WaitingApproval>()

    private val _live = MutableStateFlow<LiveAnswer?>(null)
    private val _active = MutableStateFlow<ActiveTask?>(null)
    private val _busy = MutableStateFlow(false)
    private val _pendingConversations = MutableStateFlow<Set<String>?>(null)

    private val _pendingApprovals = MutableStateFlow<Map<String, PendingApproval>>(emptyMap())
    /** Completed once crash recovery has closed the tasks of the previous run; submit waits for it. */
    private val recovered = CompletableDeferred<Unit>()

    val live: StateFlow<LiveAnswer?> = _live.asStateFlow()

    /** The task being executed, set before its first token and cleared after it ended. */
    val active: StateFlow<ActiveTask?> = _active.asStateFlow()

    /** True while a task is submitting, runnable or running. Waiting for approval does not keep it true. */
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    /** Conversations with unfinished tasks, including those waiting for approval; null during recovery. */
    val pendingConversations: StateFlow<Set<String>?> = _pendingConversations.asStateFlow()

    /** Live approvals keyed by request event ID; historical requests are never restored as live. */
    val pendingApprovals: StateFlow<Map<String, PendingApproval>> = _pendingApprovals.asStateFlow()

    init {
        scope.launch {
            try {
                recoverInterrupted()
            } finally {
                _pendingConversations.value = emptySet()
                recovered.complete(Unit)
            }
            for (signal in wake) {
                while (true) {
                    val work = synchronized(stateLock) {
                        (approved.removeFirstOrNull() ?: queue.removeFirstOrNull())?.also {
                            _active.value = ActiveTask(it.task.taskId, it.task.request.conversationId)
                        }
                    } ?: break
                    launch { execute(work) }.join()
                }
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

    private fun updatePending() {
        _pendingConversations.value = tasks.values.mapTo(mutableSetOf()) { it.request.conversationId }
        _busy.value = runnable > 0
    }

    /** Records the task and its user message, queues it, and returns the task id. */
    suspend fun submit(request: TaskRequest): String {
        val limit = maxLlmCalls()
        require(limit > 0) { "The LLM call limit must be positive" }
        recovered.await()
        val now = clock.nowMillis()
        val task = admissionLock.withLock {
            val workspace = checkNotNull(resolveWorkspace(request.conversationId)) { "Conversation workspace unavailable" }
            val task = Task(ids.next(), request, limit, workspace)
            synchronized(stateLock) {
                tasks[task.taskId] = task
                runnable++
                updatePending()
            }
            task
        }
        try {
            events.createTask(task.taskId, request, now, task.context.workspaceId)
            append(task, TaskEvent.TaskQueued(ids.next(), task.taskId, now))
            append(task, TaskEvent.UserMessage(ids.next(), task.taskId, now, request.origin, request.input))
            synchronized(stateLock) { queue.addLast(Work(task)) }
            wake.trySend(Unit)
        } catch (e: Throwable) {
            synchronized(stateLock) {
                tasks.remove(task.taskId)
                runnable--
                updatePending()
            }
            throw e
        }
        return task.taskId
    }

    /** Serializes membership changes and project deletion intent against task admission. */
    suspend fun <T> withConversationLifecycle(action: suspend (Set<String>) -> T): T {
        recovered.await()
        return admissionLock.withLock { action(checkNotNull(_pendingConversations.value)) }
    }

    /** Keeps new submissions out through cleanup; existing work must have released its resources. */
    suspend fun deleteWhenIdle(conversationId: String, delete: suspend () -> Unit): Boolean =
        withConversationLifecycle { pending ->
            if (conversationId in pending) return@withConversationLifecycle false
            withContext(NonCancellable) { delete() }
            true
        }

    /** The only cancellation path (AD-22). */
    fun cancel(taskId: String) {
        val (job, waiting) = synchronized(stateLock) {
            val task = tasks[taskId] ?: return
            if (task.cancelled) return
            task.cancelled = true
            task.job to task.waiting
        }
        job?.cancel()
        if (waiting != null) scope.launch { resolveWhenDue(waiting) }
    }

    override suspend fun resolve(approvalId: String, decision: ApprovalDecision): Boolean = approvalLock.withLock {
        val waiting = approvals[approvalId] ?: return@withLock false
        val requested = when (decision) {
            ApprovalDecision.APPROVE -> ApprovalResolution.APPROVED
            ApprovalDecision.DENY -> ApprovalResolution.DENIED
        }
        try {
            resolveWaiting(waiting, requested) == requested
        } catch (e: Exception) {
            waiting.expiryJob?.cancel()
            waiting.expiryJob = scope.launch {
                delay(APPROVAL_WRITE_RETRY_MS)
                resolveWhenDue(waiting)
            }
            throw e
        }
    }

    private suspend fun execute(work: Work) {
        val task = work.task
        val job = currentCoroutineContext().job
        synchronized(stateLock) { task.job = job }
        try {
            val result = try {
                ensureRunnable(task)
                runSlice(work)
            } finally {
                withContext(NonCancellable) { toolSessions.release(task.context) }
            }
            ensureRunnable(task)
            when (result) {
                is SliceResult.Finished -> withContext(NonCancellable) {
                    append(task, TaskEvent.AssistantMessage(ids.next(), task.taskId, clock.nowMillis(), result.text))
                    append(task, TaskEvent.TaskFinished(ids.next(), task.taskId, clock.nowMillis()))
                }
                is SliceResult.Failed -> withContext(NonCancellable) { fail(task, result.reason) }
                is SliceResult.Paused -> {
                    _live.value = null
                    publishApproval(task, result)
                }
            }
        } catch (e: Exception) {
            // A broken adapter or a failed write must end this task, not the queue or the process.
            if (synchronized(stateLock) { task.job != null }) {
                val cancelled = e is CancellationException || !currentCoroutineContext().isActive || isCancelled(task)
                withContext(NonCancellable) {
                    runCatching {
                        if (cancelled) recordCancellation(task) else fail(task, FailureReason.SERVER)
                    }
                }
            }
        } finally {
            _live.value = null
            synchronized(stateLock) {
                if (task.job != null) {
                    task.job = null
                    _active.value = null
                    tasks.remove(task.taskId)
                    runnable--
                    updatePending()
                }
            }
        }
    }

    private fun isCancelled(task: Task): Boolean = synchronized(stateLock) { task.cancelled }

    private suspend fun ensureRunnable(task: Task) {
        currentCoroutineContext().ensureActive()
        if (isCancelled(task)) throw CancellationException()
    }

    private suspend fun publishApproval(task: Task, paused: SliceResult.Paused) = approvalLock.withLock {
        ensureRunnable(task)
        withContext(NonCancellable) {
            val now = clock.nowMillis()
            val approval = ToolApproval(ids.next(), paused.call, paused.risk, now + APPROVAL_LIFETIME_MS)
            val event = TaskEvent.ApprovalRequested(ids.next(), task.taskId, now, approval)
            val elapsedDeadline = elapsedMillis() + APPROVAL_LIFETIME_MS
            val waiting = WaitingApproval(task, approval, paused.continuation, elapsedDeadline, ids.next(), event.id)
            appendOrConfirm(task, event)
            approvals[approval.id] = waiting
            synchronized(stateLock) {
                task.waiting = waiting
                task.job = null
                _active.value = null
                runnable--
                updatePending()
            }
            _pendingApprovals.value += event.id to PendingApproval(task.request.conversationId, event, elapsedDeadline)
            waiting.expiryJob = scope.launch { resolveWhenDue(waiting) }
        }
    }

    private suspend fun resolveWhenDue(waiting: WaitingApproval) {
        while (true) {
            val nextCheck = try {
                approvalLock.withLock {
                    if (approvals[waiting.approval.id] !== waiting) return
                    val remaining = waiting.elapsedDeadline - elapsedMillis()
                    if (waiting.resolution != null || remaining <= 0 || isCancelled(waiting.task)) {
                        resolveWaiting(waiting)
                        return
                    }
                    remaining
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // The request remains waiting if writing its resolution failed. Retry without dispatching.
                APPROVAL_WRITE_RETRY_MS
            }
            delay(nextCheck)
        }
    }

    /** Called with [approvalLock] held. The event must commit before the task changes state. */
    private suspend fun resolveWaiting(
        waiting: WaitingApproval,
        requested: ApprovalResolution? = null,
    ): ApprovalResolution? = withContext(NonCancellable) {
        val task = waiting.task
        val approval = waiting.approval
        val resolution = waiting.resolution ?: run {
            val now = clock.nowMillis()
            val decision = when {
                isCancelled(task) -> ApprovalResolution.CANCELLED
                elapsedMillis() >= waiting.elapsedDeadline -> ApprovalResolution.EXPIRED
                else -> requested ?: return@withContext null
            }
            TaskEvent.ApprovalResolved(waiting.resolutionId, task.taskId, now, approval.id, approval.call.call.id, decision).also {
                // Keep the same decision and event ID if the database cannot confirm the write.
                waiting.resolution = it
            }
        }
        appendOrConfirm(task, resolution) { waiting.resolution = null }
        approvals.remove(approval.id)
        _pendingApprovals.value -= waiting.requestEventId
        waiting.expiryJob?.cancel()
        val decision = resolution.decision
        when (decision) {
            ApprovalResolution.APPROVED -> {
                synchronized(stateLock) {
                    task.waiting = null
                    approved.addLast(Work(task, waiting))
                    runnable++
                    updatePending()
                }
                wake.trySend(Unit)
            }
            ApprovalResolution.DENIED -> closeWaiting(task, failedEvent(task, FailureReason.APPROVAL_DENIED))
            ApprovalResolution.EXPIRED -> closeWaiting(task, failedEvent(task, FailureReason.APPROVAL_EXPIRED))
            ApprovalResolution.CANCELLED -> closeWaiting(task, cancelledEvent(task))
        }
        decision
    }

    private suspend fun closeWaiting(task: Task, event: TaskEvent) {
        synchronized(stateLock) { task.waiting = null }
        if (appendWaitingTerminal(task, event)) return
        scope.launch {
            do {
                delay(APPROVAL_WRITE_RETRY_MS)
            } while (!withContext(NonCancellable) { appendWaitingTerminal(task, event) })
        }
    }

    private suspend fun appendWaitingTerminal(task: Task, event: TaskEvent): Boolean {
        try {
            appendOrConfirm(task, event)
        } catch (_: Exception) {
            return false
        }
        synchronized(stateLock) {
            tasks.remove(task.taskId)
            updatePending()
        }
        return true
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

    private suspend fun readReply(task: Task, request: LlmRequest): Reply {
        val text = StringBuilder()
        var fragments: TreeMap<Int, ToolFragments>? = null
        val terminal = llm.stream(request).firstOrNull { chunk ->
            when (chunk) {
                is LlmChunk.TextDelta -> {
                    text.append(chunk.text)
                    _live.value = LiveAnswer(task.taskId, task.request.conversationId, text.toString())
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
        val call: ToolCall,
    ) {
        fun describe(): TaskEvent.RequestedToolCall {
            val description = tool.describe(args)
            return TaskEvent.RequestedToolCall(call, description.copy(args = description.args.toMap()))
        }
    }

    private fun prepareCalls(
        fragments: Map<Int, ToolFragments>,
        registry: Map<String, ToolRegistry.Entry>,
    ): List<PreparedCall>? = buildList(fragments.size) {
        for ((index, fragmentsAtIndex) in fragments) {
            if (index < 0) return null
            val name = fragmentsAtIndex.name.toString()
            val tool = registry[name]?.tool ?: return null
            val argsJson = fragmentsAtIndex.arguments.toString()
            val args = parseToolArguments(argsJson) ?: return null
            add(PreparedCall(tool, args, ToolCall(ids.next(), name, argsJson)))
        }
    }

    private class ToolRound(val calls: List<PreparedCall>, val messages: MutableList<LlmMessage>) {
        var nextCall = 0
    }

    private class Continuation(
        val request: LlmRequest,
        val registry: Map<String, ToolRegistry.Entry>,
        val round: ToolRound,
    )

    private sealed interface SliceResult {
        data class Finished(val text: String) : SliceResult
        data class Failed(val reason: FailureReason) : SliceResult
        data class Paused(
            val call: TaskEvent.RequestedToolCall,
            val risk: RiskAssessment,
            val continuation: Continuation,
        ) : SliceResult
    }

    private suspend fun runSlice(work: Work): SliceResult {
        val task = work.task
        val resumed = work.resumed?.continuation
        if (resumed != null && resolveWorkspace(task.request.conversationId) != task.workspace) {
            return SliceResult.Failed(FailureReason.TOOL_DENIED)
        }
        val registry = resumed?.registry ?: tools.snapshot(hasWorkspace = task.context.workspaceId != null)
        var request = resumed?.request ?: ContextAssembler.assemble(
            events.history(task.request.conversationId),
            task.taskId,
            task.context.workspaceId?.let { instructionsForWorkspace(it) },
        ).copy(tools = registry.values.map { it.definition })
        var round = resumed?.round
        var resumedApproval = work.resumed
        // Changes during the wait can invalidate later calls. Check them before running the approved call.
        val deniedLaterCall = resumed?.round?.let {
            it.calls.subList(it.nextCall + 1, it.calls.size).firstOrNull { call ->
                call.tool.assess(call.args) == RiskAssessment.DENIED
            }
        }
        var toolTurn = round != null
        var repairNext = false
        while (true) {
            if (round == null) {
                ensureRunnable(task)
                if (task.llmCalls >= task.maxLlmCalls) return SliceResult.Failed(FailureReason.BUDGET_EXHAUSTED)
                val sent = if (repairNext) request.copy(messages = listOf(REPAIR_INSTRUCTION) + request.messages) else request
                repairNext = false
                task.llmCalls++
                val reply = readReply(task, sent)
                val failure = (reply.terminal as? LlmChunk.Failed)?.error
                if (failure != null && failure != LlmError.MalformedToolCall) return SliceResult.Failed(failure.toReason())
                val fragments = reply.fragments
                val calls = if (fragments != null && reply.terminal == LlmChunk.Done) prepareCalls(fragments, registry) else null
                if (failure == LlmError.MalformedToolCall || (fragments != null && calls == null)) {
                    _live.value = null
                    appendOrConfirm(task, TaskEvent.ToolCallRejected(ids.next(), task.taskId, clock.nowMillis(), task.llmCalls))
                    if (task.repaired) return SliceResult.Failed(FailureReason.MALFORMED_TOOL_CALL)
                    if (task.llmCalls >= task.maxLlmCalls) return SliceResult.Failed(FailureReason.BUDGET_EXHAUSTED)
                    appendOrConfirm(task, TaskEvent.LlmRetry(ids.next(), task.taskId, clock.nowMillis(), task.llmCalls + 1))
                    task.repaired = true
                    repairNext = true
                    continue
                }
                if (calls == null) {
                    return if (reply.text.isEmpty() || (toolTurn && reply.terminal != LlmChunk.Done)) {
                        SliceResult.Failed(FailureReason.SERVER)
                    } else {
                        SliceResult.Finished(reply.text)
                    }
                }
                val denied = calls.firstOrNull { it.tool.assess(it.args) == RiskAssessment.DENIED }
                _live.value = null
                val event = TaskEvent.ToolCallRequested(
                    ids.next(), task.taskId, clock.nowMillis(),
                    if (denied == null) reply.text else "",
                    calls.map { it.describe() },
                    deniedCallIds = if (denied == null) emptyList() else listOf(denied.call.id),
                )
                append(task, event)
                if (denied != null) return SliceResult.Failed(FailureReason.TOOL_DENIED)
                round = ToolRound(calls, (request.messages + event.toLlmMessage()).toMutableList())
            }

            while (round.nextCall < round.calls.size) {
                ensureRunnable(task)
                val call = round.calls[round.nextCall]
                val risk = call.tool.assess(call.args)
                if (resumedApproval != null) {
                    val approval = resumedApproval.approval
                    if (risk == RiskAssessment.DENIED) recordToolDenial(task, call.call.id)
                    if (risk != approval.risk || call.describe() != approval.call) {
                        return SliceResult.Failed(FailureReason.APPROVAL_CHANGED)
                    }
                    if (elapsedMillis() >= resumedApproval.elapsedDeadline) {
                        return SliceResult.Failed(FailureReason.APPROVAL_EXPIRED)
                    }
                    if (deniedLaterCall != null) {
                        recordToolDenial(task, deniedLaterCall.call.id)
                        return SliceResult.Failed(FailureReason.TOOL_DENIED)
                    }
                    resumedApproval = null
                } else {
                    when (risk) {
                        RiskAssessment.READ_ONLY, RiskAssessment.LOCAL_WRITE -> Unit
                        RiskAssessment.DENIED -> {
                            recordToolDenial(task, call.call.id)
                            return SliceResult.Failed(FailureReason.TOOL_DENIED)
                        }
                        RiskAssessment.DESTRUCTIVE, RiskAssessment.FINANCIAL, RiskAssessment.EGRESS, RiskAssessment.PERSISTENT ->
                            return SliceResult.Paused(call.describe(), risk, Continuation(request, registry, round))
                    }
                }
                ensureRunnable(task)
                val outcome = call.tool.execute(call.args, task.context)
                val result = TaskEvent.ToolResult(ids.next(), task.taskId, clock.nowMillis(), call.call.id, outcome)
                withContext(NonCancellable) { append(task, result) }
                round.messages.add(result.toLlmMessage())
                round.nextCall++
            }
            request = request.copy(messages = round.messages.toList())
            round = null
            toolTurn = true
            _live.value = null
        }
    }

    private suspend fun recordToolDenial(task: Task, callId: String) =
        append(task, TaskEvent.ToolExecutionDenied(ids.next(), task.taskId, clock.nowMillis(), callId))

    private fun failedEvent(task: Task, reason: FailureReason) =
        TaskEvent.TaskFailed(ids.next(), task.taskId, clock.nowMillis(), reason)

    private fun cancelledEvent(task: Task) = TaskEvent.TaskCancelled(ids.next(), task.taskId, clock.nowMillis())

    private suspend fun fail(task: Task, reason: FailureReason) = append(task, failedEvent(task, reason))

    private suspend fun recordCancellation(task: Task) = append(task, cancelledEvent(task))

    private suspend fun append(task: Task, event: TaskEvent) = events.append(task.request.conversationId, event)

    private suspend fun appendOrConfirm(task: Task, event: TaskEvent, onConfirmedAbsent: () -> Unit = {}) {
        try {
            append(task, event)
        } catch (e: Exception) {
            // Storage can report an error after commit, so confirm by the event's unique ID.
            val stored = runCatching { events.history(task.request.conversationId).any { it.id == event.id } }.getOrNull()
            if (stored == false) onConfirmedAbsent()
            if (stored != true) throw e
        }
    }

    private fun LlmError.toReason(): FailureReason = when (this) {
        is LlmError.AuthFailed -> FailureReason.AUTH_FAILED
        is LlmError.RateLimited -> FailureReason.RATE_LIMITED
        is LlmError.QuotaExhausted -> FailureReason.QUOTA_EXHAUSTED
        is LlmError.Network -> FailureReason.NETWORK
        is LlmError.ToolsUnsupported, is LlmError.Server -> FailureReason.SERVER
        is LlmError.MalformedToolCall -> FailureReason.MALFORMED_TOOL_CALL
    }

    private companion object {
        const val APPROVAL_LIFETIME_MS = 12 * 60 * 60 * 1_000L
        const val APPROVAL_WRITE_RETRY_MS = 1_000L
        val REPAIR_INSTRUCTION = LlmMessage(
            Role.SYSTEM,
            "Your previous tool call reply was malformed. Reply with complete calls to the available tools " +
                "using valid JSON objects for every call's arguments, or answer in plain text.",
        )
    }
}
