package dev.lusalhi.lumynoid.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

private class InMemoryEvents : EventRepository {
    val state = MutableStateFlow<List<TaskEvent>>(emptyList())
    val tasks = mutableListOf<String>()
    val conversations = linkedMapOf<String, String>()
    override suspend fun createTask(taskId: String, request: TaskRequest, at: Long) {
        tasks += taskId
        conversations[taskId] = request.conversationId
    }
    override suspend fun append(conversationId: String, event: TaskEvent) { state.value = state.value + event }
    override fun observe(conversationId: String): Flow<List<TaskEvent>> = state
    override suspend fun history(conversationId: String): List<TaskEvent> = state.value

    override suspend fun unfinishedTasks(): List<UnfinishedTask> = conversations.mapNotNull { (taskId, conv) ->
        val last = state.value.lastOrNull { it.taskId == taskId }
        val open = last == null || last.needsRecovery
        if (open) UnfinishedTask(taskId, conv) else null
    }

    /** Seeds a task of a previous run. */
    fun seed(taskId: String, vararg events: TaskEvent) {
        conversations[taskId] = "conv-1"
        state.value = state.value + events
    }
}

private class Counter : IdGenerator {
    var n = 0
    override fun next() = "id-${n++}"
}

private class FixtureTool(
    override val name: String = "web_fixture_lookup",
    private val risk: RiskAssessment = RiskAssessment.READ_ONLY,
    private val properties: Map<String, JsonObject> = emptyMap(),
    private val run: suspend (JsonObject, ToolContext) -> ToolOutcome,
) : Tool {
    override val description = "Look up in-memory test data"
    override val inputSchema = JsonObject(mapOf("properties" to JsonObject(properties)))
    override fun assess(args: JsonObject): RiskAssessment =
        if (args.keys.any { it !in properties } || args.values.any { it !is JsonPrimitive || !it.isString }) {
            RiskAssessment.DENIED
        } else {
            risk
        }
    override fun describe(args: JsonObject) = UiText("card.fixture.lookup")
    override suspend fun execute(args: JsonObject, context: ToolContext) = run(args, context)
}

@OptIn(ExperimentalCoroutinesApi::class)
class TaskRunnerTest {
    private val request = TaskRequest(Origin.User, "conv-1", "ws-1", "hello")

    private fun runnerFor(
        llm: LlmPort,
        events: InMemoryEvents,
        scope: CoroutineScope,
        tools: ToolRegistry = ToolRegistry(),
    ) = TaskRunner(llm, events, scope, Clock { 1L }, Counter(), tools)

    /**
     * `backgroundScope` work is ignored by `advanceUntilIdle`, so the runner gets a regular child
     * scope on the test scheduler that is cancelled when the test body ends.
     */
    private fun runnerTest(block: suspend TestScope.(CoroutineScope) -> Unit) = runTest {
        val job = Job()
        try {
            block(CoroutineScope(StandardTestDispatcher(testScheduler) + job))
        } finally {
            job.cancel()
        }
    }

    @Test
    fun fragmentedToolRoundsUseOneSnapshotAndCorrelateResultsBeforeTheNextQueuedMessage() = runnerTest { scope ->
        val events = InMemoryEvents()
        val requests = mutableListOf<LlmRequest>()
        val invocations = mutableListOf<Pair<String, ToolContext>>()
        val enter = CompletableDeferred<Unit>()
        val proceed = CompletableDeferred<Unit>()
        val properties = mutableMapOf("key" to JsonObject(mapOf("type" to JsonPrimitive("string"))))
        val tool = FixtureTool(properties = properties) { args, context ->
            val key = args.getValue("key").jsonPrimitive.content
            assertTrue(events.state.value.any { it is TaskEvent.ToolCallRequested && it.taskId == context.taskId })
            invocations += key to context
            ToolOutcome("fixture:$key", target = key)
        }
        var available = listOf<Tool>(tool)
        val registry = ToolRegistry(listOf(ToolProvider { available }))
        val llm = LlmPort { request ->
            flow {
                requests += request
                when (requests.size) {
                    1 -> {
                        enter.complete(Unit)
                        proceed.await()
                        available = emptyList()
                        val original = properties.getValue("key")
                        properties["key"] = JsonObject(mapOf("type" to JsonPrimitive("number")))
                        assertEquals(original, (request.tools.single().inputSchema.getValue("properties") as JsonObject)["key"])
                        emit(LlmChunk.TextDelta("looking"))
                        emit(LlmChunk.ToolCallDelta(2, "web_fixture_", """{"key":"ri"""))
                        emit(LlmChunk.ToolCallDelta(0, tool.name, """{"key":"left"}"""))
                        emit(LlmChunk.ToolCallDelta(2, "lookup", """ght"}"""))
                    }
                    2 -> {
                        assertEquals(listOf("fixture:left", "fixture:right"), request.messages.takeLast(2).map { it.content })
                        emit(LlmChunk.ToolCallDelta(0, tool.name, """{"key":"third"}"""))
                    }
                    3 -> {
                        val outputs = request.messages.filter { it.role == Role.TOOL }.map { it.content }
                        emit(LlmChunk.TextDelta(outputs.joinToString()))
                    }
                    else -> emit(LlmChunk.TextDelta("plain reply"))
                }
                emit(LlmChunk.Done)
            }
        }
        val runner = runnerFor(llm, events, scope, registry)
        val taskId = runner.submit(request)
        enter.await()
        val laterId = runner.submit(request.copy(input = "later"))
        proceed.complete(Unit)
        advanceUntilIdle()

        val taskEvents = events.state.value.filter { it.taskId == taskId }
        val rounds = taskEvents.filterIsInstance<TaskEvent.ToolCallRequested>()
        val results = taskEvents.filterIsInstance<TaskEvent.ToolResult>()
        val calls = rounds.flatMap { it.calls }.map { it.call }
        assertEquals(3, calls.map { it.id }.toSet().size)
        assertEquals(listOf("left", "right", "third"), invocations.map { it.first })
        assertEquals(listOf(ToolContext(taskId, "conv-1", "ws-1")), invocations.map { it.second }.distinct())
        assertEquals(calls.map { it.id }, results.map { it.callId })
        assertEquals(calls.map { it.id }, requests[2].messages.filter { it.role == Role.TOOL }.map { it.toolCallId })
        assertEquals(calls, requests[2].messages.flatMap { it.toolCalls })
        assertEquals("looking", requests[1].messages[1].content)
        assertEquals(listOf("hello"), requests[2].messages.filter { it.role == Role.USER }.map { it.content })
        assertEquals(List(3) { listOf(tool.name) } + listOf(emptyList<String>()), requests.map { it.tools.map { spec -> spec.name } })
        assertEquals(requests[0].tools, requests[1].tools)
        assertEquals(requests[0].tools, requests[2].tools)
        val finalAnswer = "fixture:left, fixture:right, fixture:third"
        assertEquals(finalAnswer, taskEvents.filterIsInstance<TaskEvent.AssistantMessage>().single().text)
        assertIs<TaskEvent.TaskFinished>(taskEvents.last())
        assertEquals(finalAnswer, requests[3].messages.dropLast(1).last().content)
        assertEquals("later", requests[3].messages.last().content)
        assertEquals(calls.map { it.id }, requests[3].messages.filter { it.role == Role.TOOL }.map { it.toolCallId })
        assertEquals("plain reply", events.state.value.filterIsInstance<TaskEvent.AssistantMessage>().single { it.taskId == laterId }.text)
    }

    @Test
    fun malformedIncompleteOrRiskyToolBatchesNeverExecuteTheValidPrefix() = runnerTest { scope ->
        val events = InMemoryEvents()
        var executed = 0
        val safe = FixtureTool(properties = mapOf("key" to JsonObject(emptyMap()))) { _, _ ->
            executed++
            ToolOutcome("unexpected")
        }
        val risky = FixtureTool("web_fixture_egress", RiskAssessment.EGRESS) { _, _ ->
            executed++
            ToolOutcome("unexpected")
        }
        val registry = ToolRegistry(listOf(ToolProvider { listOf(safe, risky) }))
        val llm = LlmPort { request ->
            flow {
                emit(LlmChunk.ToolCallDelta(0, safe.name, "{}"))
                when (request.messages.last().content) {
                    "malformed" -> emit(LlmChunk.ToolCallDelta(1, safe.name, "{"))
                    "bare" -> emit(LlmChunk.ToolCallDelta(1, safe.name, """{"key":bogus,"key":"valid"}"""))
                    "schema" -> emit(LlmChunk.ToolCallDelta(1, safe.name, """{"x":"unexpected"}"""))
                    "risky" -> emit(LlmChunk.ToolCallDelta(1, risky.name, "{}"))
                    "truncated" -> return@flow
                }
                emit(LlmChunk.Done)
            }
        }
        val runner = runnerFor(llm, events, scope, registry)
        val taskIds = listOf("malformed", "bare", "schema", "risky", "truncated")
            .map { runner.submit(request.copy(input = it)) }
        advanceUntilIdle()

        assertEquals(0, executed)
        assertTrue(events.state.value.none { it is TaskEvent.ToolCallRequested || it is TaskEvent.ToolResult })
        assertEquals(taskIds, events.state.value.filterIsInstance<TaskEvent.TaskFailed>().map { it.taskId })
    }

    @Test
    fun truncatedTextAfterAToolRoundDoesNotFinishTheTask() = runnerTest { scope ->
        val events = InMemoryEvents()
        val tool = FixtureTool { _, _ -> ToolOutcome("fixture result") }
        val llm = LlmPort { request ->
            flow {
                if (request.messages.last().role == Role.TOOL) {
                    emit(LlmChunk.TextDelta("partial"))
                } else {
                    emit(LlmChunk.ToolCallDelta(0, tool.name, "{}"))
                    emit(LlmChunk.Done)
                }
            }
        }
        val runner = runnerFor(llm, events, scope, ToolRegistry(listOf(ToolProvider { listOf(tool) })))
        val id = runner.submit(request)
        advanceUntilIdle()

        val log = events.state.value.filter { it.taskId == id }
        assertEquals("fixture result", log.filterIsInstance<TaskEvent.ToolResult>().single().outcome.output)
        assertTrue(log.none { it is TaskEvent.AssistantMessage || it is TaskEvent.TaskFinished })
        assertIs<TaskEvent.TaskFailed>(log.last())
    }

    @Test
    fun streamingAnswerIsPersistedAsMessageThenFinished() = runnerTest { scope ->
        val events = InMemoryEvents()
        val seen = mutableListOf<String>()
        val llm = LlmPort { req ->
            seen += req.messages.map { it.content }
            flow {
                emit(LlmChunk.Routed("p", "m"))
                emit(LlmChunk.TextDelta("Hel"))
                emit(LlmChunk.TextDelta("lo!"))
                emit(LlmChunk.Done)
            }
        }
        val runner = runnerFor(llm, events, scope)

        runner.submit(request)
        advanceUntilIdle()

        val types = events.state.value.map { it::class.simpleName }
        assertEquals(listOf("TaskQueued", "UserMessage", "AssistantMessage", "TaskFinished"), types)
        assertEquals("Hello!", (events.state.value[2] as TaskEvent.AssistantMessage).text)
        assertEquals(listOf("hello"), seen)
        assertEquals(null, runner.live.value)
    }

    @Test
    fun authFailureEndsTaskWithTypedReason() = runnerTest { scope ->
        val events = InMemoryEvents()
        val llm = LlmPort { flow { emit(LlmChunk.Failed(LlmError.AuthFailed)) } }
        val runner = runnerFor(llm, events, scope)

        runner.submit(request)
        advanceUntilIdle()

        val last = events.state.value.last()
        assertIs<TaskEvent.TaskFailed>(last)
        assertEquals(FailureReason.AUTH_FAILED, last.reason)
        assertTrue(events.state.value.none { it is TaskEvent.AssistantMessage })
    }

    @Test
    fun networkFailureEndsTaskWithNetworkReason() = runnerTest { scope ->
        val events = InMemoryEvents()
        val llm = LlmPort { flow { emit(LlmChunk.Failed(LlmError.Network)) } }
        val runner = runnerFor(llm, events, scope)

        runner.submit(request)
        advanceUntilIdle()

        assertEquals(FailureReason.NETWORK, (events.state.value.last() as TaskEvent.TaskFailed).reason)
    }

    @Test
    fun tasksRunOneAtATimeInSubmissionOrder() = runnerTest { scope ->
        val events = InMemoryEvents()
        val order = mutableListOf<String>()
        val llm = LlmPort { req ->
            flow {
                order += "start:${req.messages.last().content}"
                emit(LlmChunk.TextDelta("x"))
                order += "end:${req.messages.last().content}"
                emit(LlmChunk.Done)
            }
        }
        val runner = runnerFor(llm, events, scope)

        runner.submit(request.copy(input = "a"))
        runner.submit(request.copy(input = "b"))
        advanceUntilIdle()

        assertEquals(listOf("start:a", "end:a", "start:b", "end:b"), order)
    }

    @Test
    fun aFailingAdapterEndsOnlyThatTaskAndTheQueueKeepsRunning() = runnerTest { scope ->
        val events = InMemoryEvents()
        var calls = 0
        val llm = LlmPort { req ->
            flow {
                if (calls++ == 0) throw IllegalStateException("adapter blew up")
                emit(LlmChunk.TextDelta("fine:" + req.messages.last().content))
                emit(LlmChunk.Done)
            }
        }
        val runner = runnerFor(llm, events, scope)

        runner.submit(request.copy(input = "first"))
        runner.submit(request.copy(input = "second"))
        advanceUntilIdle()

        val failed = events.state.value.filterIsInstance<TaskEvent.TaskFailed>().single()
        assertEquals(FailureReason.SERVER, failed.reason)
        assertEquals("fine:second", events.state.value.filterIsInstance<TaskEvent.AssistantMessage>().single().text)
    }

    @Test
    fun liveAnswerGrowsWhileStreamingAndClearsWhenDone() = runnerTest { scope ->
        val events = InMemoryEvents()
        val first = CompletableDeferred<Unit>()
        val second = CompletableDeferred<Unit>()
        val llm = LlmPort {
            flow {
                emit(LlmChunk.TextDelta("a"))
                first.await()
                emit(LlmChunk.TextDelta("b"))
                second.await()
                emit(LlmChunk.Done)
            }
        }
        val runner = runnerFor(llm, events, scope)

        runner.submit(request)
        advanceUntilIdle()
        assertEquals("a", runner.live.value?.text)
        assertEquals("conv-1", runner.live.value?.conversationId)

        first.complete(Unit)
        advanceUntilIdle()
        assertEquals("ab", runner.live.value?.text)

        second.complete(Unit)
        advanceUntilIdle()
        assertEquals(null, runner.live.value)
        assertEquals("ab", events.state.value.filterIsInstance<TaskEvent.AssistantMessage>().single().text)
    }

    @Test
    fun anAnswerWithNoTextFailsInsteadOfSucceeding() = runnerTest { scope ->
        val events = InMemoryEvents()
        val runner = runnerFor(LlmPort { flow { emit(LlmChunk.Done) } }, events, scope)

        runner.submit(request)
        advanceUntilIdle()

        assertEquals(FailureReason.SERVER, (events.state.value.last() as TaskEvent.TaskFailed).reason)
        assertTrue(events.state.value.none { it is TaskEvent.AssistantMessage })
    }

    @Test
    fun cancellingAQueuedTaskNeverCallsTheModel() = runnerTest { scope ->
        val events = InMemoryEvents()
        val gate = CompletableDeferred<Unit>()
        val called = mutableListOf<String>()
        val llm = LlmPort { req ->
            flow {
                val input = req.messages.last().content
                called += input
                if (input == "first") gate.await()
                emit(LlmChunk.TextDelta("done"))
                emit(LlmChunk.Done)
            }
        }
        val runner = runnerFor(llm, events, scope)

        runner.submit(request.copy(input = "first"))
        val queued = runner.submit(request.copy(input = "second"))
        advanceUntilIdle()
        runner.cancel(queued)
        gate.complete(Unit)
        advanceUntilIdle()

        assertEquals(listOf("first"), called)
        val cancelled = events.state.value.filterIsInstance<TaskEvent.TaskCancelled>().single()
        assertEquals(queued, cancelled.taskId)
        assertEquals(1, events.state.value.filterIsInstance<TaskEvent.TaskFinished>().size)
    }

    @Test
    fun queuedConversationsStayPendingUntilTheirLastTaskFinishes() = runnerTest { scope ->
        val first = CompletableDeferred<Unit>()
        val last = CompletableDeferred<Unit>()
        val llm = LlmPort { request ->
            flow {
                when (request.messages.last().content) {
                    "first" -> first.await()
                    "last" -> last.await()
                }
                emit(LlmChunk.TextDelta("done"))
            }
        }
        val runner = runnerFor(llm, InMemoryEvents(), scope)
        val other = request.copy(conversationId = "conv-2")

        runner.submit(request.copy(input = "first"))
        runner.submit(other.copy(input = "second"))
        runner.submit(other.copy(input = "last"))
        advanceUntilIdle()

        assertEquals(request.conversationId, runner.active.value?.conversationId)
        assertEquals(setOf(request.conversationId, other.conversationId), runner.pendingConversations.value)

        first.complete(Unit)
        advanceUntilIdle()
        assertEquals(setOf(other.conversationId), runner.pendingConversations.value)

        last.complete(Unit)
        advanceUntilIdle()
        assertEquals(emptySet(), runner.pendingConversations.value)
    }

    @Test
    fun cancellingTheRunningTaskRecordsCancellation() = runnerTest { scope ->
        val events = InMemoryEvents()
        val llm = LlmPort {
            flow {
                emit(LlmChunk.TextDelta("partial"))
                awaitCancellation()
            }
        }
        val runner = runnerFor(llm, events, scope)

        val id = runner.submit(request)
        advanceUntilIdle()
        runner.cancel(id)
        advanceUntilIdle()

        assertIs<TaskEvent.TaskCancelled>(events.state.value.last())
        assertEquals(null, runner.live.value)
    }

    @Test
    fun aLaterMessageCarriesEveryEarlierAnsweredTurn() = runnerTest { scope ->
        val events = InMemoryEvents()
        val requests = mutableListOf<List<LlmMessage>>()
        val llm = LlmPort { req ->
            requests += req.messages
            flow { emit(LlmChunk.TextDelta("re:" + req.messages.last().content)); emit(LlmChunk.Done) }
        }
        val runner = runnerFor(llm, events, scope)

        runner.submit(request.copy(input = "a"))
        runner.submit(request.copy(input = "b"))
        advanceUntilIdle()

        assertEquals(
            listOf(LlmMessage(Role.USER, "a"), LlmMessage(Role.ASSISTANT, "re:a"), LlmMessage(Role.USER, "b")),
            requests[1],
        )
    }

    private fun queued(t: String) = TaskEvent.TaskQueued("q-$t", t, 0L)
    private fun user(t: String) = TaskEvent.UserMessage("u-$t", t, 0L, Origin.User, "x")
    private fun answer(t: String) = TaskEvent.AssistantMessage("a-$t", t, 0L, "x")

    @Test
    fun recoveryClosesOnlyTasksWithoutATerminalEvent() = runnerTest { scope ->
        val events = InMemoryEvents()
        events.seed("queued-only", queued("queued-only"))
        events.seed("user-only", queued("user-only"), user("user-only"))
        events.seed("answered", queued("answered"), user("answered"), answer("answered"))
        val call = ToolCall("call", "web_fixture_lookup", "{}")
        fun toolRequest(taskId: String) = TaskEvent.ToolCallRequested(
            "request-$taskId", taskId, 0L, "", listOf(TaskEvent.RequestedToolCall(call, UiText("card.fixture.lookup"))),
        )
        events.seed("requested", user("requested"), toolRequest("requested"))
        events.seed(
            "result", user("result"), toolRequest("result"),
            TaskEvent.ToolResult("tool-result", "result", 0L, call.id, ToolOutcome("stored")),
        )
        events.seed("no-events")
        events.seed("done", queued("done"), user("done"), answer("done"), TaskEvent.TaskFinished("f-done", "done", 0L))
        events.seed("failed", user("failed"), TaskEvent.TaskFailed("x-failed", "failed", 0L, FailureReason.NETWORK))
        events.seed("stopped", user("stopped"), TaskEvent.TaskCancelled("c-stopped", "stopped", 0L))
        events.seed("unknown", user("unknown"), TaskEvent.Unknown("k", "unknown", 0L))
        val before = events.state.value.size
        var modelCalls = 0
        var toolCalls = 0
        val tool = FixtureTool { _, _ -> toolCalls++; ToolOutcome("unexpected") }
        runnerFor(
            LlmPort { modelCalls++; flow { emit(LlmChunk.Done) } }, events, scope,
            ToolRegistry(listOf(ToolProvider { listOf(tool) })),
        )

        advanceUntilIdle()

        val added = events.state.value.drop(before)
        assertTrue(added.all { it is TaskEvent.TaskFailed && it.reason == FailureReason.INTERRUPTED })
        assertEquals(setOf("queued-only", "user-only", "answered", "requested", "result", "no-events"), added.map { it.taskId }.toSet())
        assertEquals(6, added.size)
        assertEquals(0, modelCalls)
        assertEquals(0, toolCalls)
    }

    @Test
    fun aSubmitDuringRecoveryIsNeverMarkedInterrupted() = runnerTest { scope ->
        val events = InMemoryEvents()
        events.seed("old", queued("old"), user("old"))
        val llm = LlmPort { flow { emit(LlmChunk.TextDelta("ok")); emit(LlmChunk.Done) } }
        val runner = runnerFor(llm, events, scope)

        val id = runner.submit(request)
        advanceUntilIdle()

        val interrupted = events.state.value.filterIsInstance<TaskEvent.TaskFailed>()
        assertEquals(listOf("old"), interrupted.map { it.taskId })
        assertIs<TaskEvent.TaskFinished>(events.state.value.last { it.taskId == id })
    }

    @Test
    fun aFailingRecoveryStillLetsTheQueueRun() = runnerTest { scope ->
        val log = InMemoryEvents()
        val events = object : EventRepository by log {
            override suspend fun unfinishedTasks(): List<UnfinishedTask> = error("disk gone")
        }
        val llm = LlmPort { flow { emit(LlmChunk.TextDelta("ok")); emit(LlmChunk.Done) } }
        val runner = TaskRunner(llm, events, scope, Clock { 1L }, Counter())

        runner.submit(request)
        advanceUntilIdle()

        assertIs<TaskEvent.TaskFinished>(log.state.value.last())
    }

    @Test
    fun busyStaysTrueAcrossQueuedTasksAndDropsAfterTheLast() = runnerTest { scope ->
        val events = InMemoryEvents()
        val gate = CompletableDeferred<Unit>()
        val llm = LlmPort { req ->
            flow {
                if (req.messages.last().content == "a") gate.await()
                emit(LlmChunk.TextDelta("x"))
                emit(LlmChunk.Done)
            }
        }
        val runner = runnerFor(llm, events, scope)
        val seen = mutableListOf<Boolean>()
        val watcher = scope.launch(UnconfinedTestDispatcher(testScheduler)) { runner.busy.collect { seen += it } }
        advanceUntilIdle()
        assertEquals(false, runner.busy.value)

        runner.submit(request.copy(input = "a"))
        runner.submit(request.copy(input = "b"))
        advanceUntilIdle()
        assertEquals(true, runner.busy.value)
        gate.complete(Unit)
        advanceUntilIdle()

        assertEquals(false, runner.busy.value)
        assertEquals(listOf(false, true, false), seen)
        watcher.cancel()
    }

    @Test
    fun activeIsSetBeforeTheFirstTokenAndClearedAfterwards() = runnerTest { scope ->
        val events = InMemoryEvents()
        val gate = CompletableDeferred<Unit>()
        val llm = LlmPort { flow { gate.await(); emit(LlmChunk.TextDelta("x")); emit(LlmChunk.Done) } }
        val runner = runnerFor(llm, events, scope)

        val id = runner.submit(request)
        advanceUntilIdle()
        assertEquals(ActiveTask(id, "conv-1"), runner.active.value)
        assertEquals(null, runner.live.value)

        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(null, runner.active.value)
    }

    @Test
    fun cancellingATaskThatAlreadyFinishedChangesNothing() = runnerTest { scope ->
        val events = InMemoryEvents()
        val llm = LlmPort { flow { emit(LlmChunk.TextDelta("x")); emit(LlmChunk.Done) } }
        val runner = runnerFor(llm, events, scope)

        val done = runner.submit(request)
        advanceUntilIdle()
        val before = events.state.value
        runner.cancel(done)
        advanceUntilIdle()
        assertEquals(before, events.state.value)

        val next = runner.submit(request.copy(input = "again"))
        advanceUntilIdle()
        assertIs<TaskEvent.TaskFinished>(events.state.value.last { it.taskId == next })
    }

    @Test
    fun cancellingBeforeTheFirstTokenRecordsCancellationAndStartsTheNextTask() = runnerTest { scope ->
        val events = InMemoryEvents()
        val llm = LlmPort { req ->
            flow {
                if (req.messages.last().content == "a") awaitCancellation()
                emit(LlmChunk.TextDelta("b-answer"))
                emit(LlmChunk.Done)
            }
        }
        val runner = runnerFor(llm, events, scope)

        val a = runner.submit(request.copy(input = "a"))
        runner.submit(request.copy(input = "b"))
        advanceUntilIdle()
        runner.cancel(a)
        advanceUntilIdle()

        assertTrue(events.state.value.any { it is TaskEvent.TaskCancelled && it.taskId == a })
        assertTrue(events.state.value.none { it is TaskEvent.AssistantMessage && it.taskId == a })
        assertEquals("b-answer", events.state.value.filterIsInstance<TaskEvent.AssistantMessage>().single().text)
        assertEquals(false, runner.busy.value)
    }

    @Test
    fun eventsRoundTripThroughJsonAndUnknownTypesDecodeToUnknown() {
        val original = TaskEvent.UserMessage("e1", "t1", 5L, Origin.User, "hi")
        val text = TaskEventJson.encode(original)
        assertTrue(text.contains("\"type\":\"user_message\""))
        assertEquals(original, TaskEventJson.decode(text))

        val future = TaskEventJson.decode("""{"type":"from_the_future","id":"e9","taskId":"t9","at":7,"extra":1}""")
        assertIs<TaskEvent.Unknown>(future)
    }
}
