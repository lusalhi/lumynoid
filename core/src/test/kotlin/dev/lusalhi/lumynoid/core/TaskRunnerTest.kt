package dev.lusalhi.lumynoid.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

private class InMemoryEvents : EventRepository {
    val state = MutableStateFlow<List<TaskEvent>>(emptyList())
    val tasks = mutableListOf<String>()
    val conversations = linkedMapOf<String, String>()
    override suspend fun createTask(taskId: String, request: TaskRequest, at: Long, workspaceId: String?) {
        tasks += taskId
        conversations[taskId] = request.conversationId
    }
    override suspend fun append(conversationId: String, event: TaskEvent) { state.value = state.value + event }
    override fun observe(conversationId: String): Flow<List<TaskEvent>> =
        state.map { events -> events.filter { conversations[it.taskId] == conversationId } }
    override suspend fun history(conversationId: String): List<TaskEvent> =
        state.value.filter { conversations[it.taskId] == conversationId }

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
    override val requiresWorkspace: Boolean = false,
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
    private val request = TaskRequest(Origin.User, "conv-1", "hello")
    private val fixtureWorkspace: suspend (String) -> ConversationWorkspace = { ConversationWorkspace.Project("ws-1") }

    private fun runnerFor(
        llm: LlmPort,
        events: InMemoryEvents,
        scope: CoroutineScope,
        tools: ToolRegistry = ToolRegistry(),
        instructions: suspend (String) -> String? = { null },
    ) = TaskRunner(
        llm, events, scope, Clock { 1L }, Counter(), tools,
        resolveWorkspace = fixtureWorkspace, instructionsForWorkspace = instructions,
    )

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
    fun plainToolSnapshotValidatesHiddenWorkspaceRegistrations() {
        fun tool(name: String, workspace: Boolean) =
            FixtureTool(name, requiresWorkspace = workspace) { _, _ -> error("Tools must not execute during registration") }
        fun registry(vararg tools: Tool) = ToolRegistry(tools.map { tool -> ToolProvider { listOf(tool) } })

        val plain = tool("web_public", false)
        val project = tool("shell_private", true)
        assertEquals(setOf(plain.name), registry(plain, project).snapshot(hasWorkspace = false).keys)
        for (name in listOf("Invalid name", "missing_prefix")) {
            assertFailsWith<IllegalArgumentException> { registry(tool(name, true)).snapshot(hasWorkspace = false) }
        }
        val duplicate = tool(project.name, false)
        for (entries in listOf(arrayOf(project, duplicate), arrayOf(duplicate, project), arrayOf(project, project))) {
            assertFailsWith<IllegalStateException> { registry(*entries).snapshot(hasWorkspace = false) }
        }
    }

    @Test
    fun workspaceToolsShareOnlyProjectFilesWhileModelHistoryRemainsConversationScoped() = runnerTest { scope ->
        val events = InMemoryEvents()
        val files = mutableMapOf<String, String>()
        val workspaceCalls = mutableListOf<String>()
        val requests = mutableListOf<LlmRequest>()
        val tool = FixtureTool("shell_fixture", requiresWorkspace = true, properties = mapOf("write" to JsonObject(emptyMap()))) { args, context ->
            val workspace = checkNotNull(context.workspaceId)
            workspaceCalls += workspace
            args["write"]?.jsonPrimitive?.content?.let { files[workspace] = it }
            ToolOutcome(files[workspace] ?: "missing")
        }
        val llm = LlmPort { request ->
            requests += request
            flow {
                val message = request.messages.last()
                when {
                    message.role == Role.TOOL -> emit(LlmChunk.TextDelta("saw ${message.content}"))
                    message.content == "plain greeting" -> emit(LlmChunk.TextDelta("plain reply"))
                    else -> emit(LlmChunk.ToolCallDelta(0, tool.name, if (message.content == "private first prompt") {
                        """{"write":"shared file"}"""
                    } else "{}"))
                }
                emit(LlmChunk.Done)
            }
        }
        val runner = TaskRunner(llm, events, scope, tools = registryFor(tool), resolveWorkspace = {
            when (it) {
                "a1", "a2" -> ConversationWorkspace.Project("project-a")
                "b" -> ConversationWorkspace.Project("project-b")
                "plain" -> ConversationWorkspace.Plain
                else -> null
            }
        })
        suspend fun turn(id: String, input: String): TaskEvent {
            val task = runner.submit(TaskRequest(Origin.User, id, input))
            runCurrent()
            return events.history(id).last { it.taskId == task }
        }
        assertIs<TaskEvent.TaskFinished>(turn("a1", "private first prompt"))
        assertIs<TaskEvent.TaskFinished>(turn("a2", "read A"))
        assertEquals(listOf("read A"), requests.first { it.messages.last().content == "read A" }.messages.map { it.content })
        assertEquals("saw shared file", events.history("a2").filterIsInstance<TaskEvent.AssistantMessage>().single().text)

        assertIs<TaskEvent.TaskFinished>(turn("b", "read B"))
        assertEquals("saw missing", events.history("b").filterIsInstance<TaskEvent.AssistantMessage>().single().text)
        assertEquals(FailureReason.MALFORMED_TOOL_CALL, assertIs<TaskEvent.TaskFailed>(turn("plain", "try workspace")).reason)
        assertIs<TaskEvent.TaskFinished>(turn("plain", "plain greeting"))
        assertEquals("plain reply", events.history("plain").filterIsInstance<TaskEvent.AssistantMessage>().single().text)
        assertTrue(requests.filter { it.messages.last().content in setOf("try workspace", "plain greeting") }.all { it.tools.isEmpty() })
        assertEquals(listOf("project-a", "project-a", "project-b"), workspaceCalls)

        val callsBefore = requests.size
        assertFailsWith<IllegalStateException> { runner.submit(TaskRequest(Origin.User, "missing", "must not start")) }
        runCurrent()
        assertTrue(events.history("missing").isEmpty())
        assertEquals(callsBefore, requests.size)
    }

    @Test
    fun approvalCannotDispatchAgainstAChangedWorkspace() = runnerTest { scope ->
        val events = InMemoryEvents()
        val tool = FixtureTool("shell_fixture_replace", RiskAssessment.DESTRUCTIVE, requiresWorkspace = true) { _, _ ->
            error("Approval from one workspace must not authorize a write in another")
        }
        var workspace = ConversationWorkspace.Project("project-a")
        val runner = TaskRunner(
            toolReply(tool), events, scope, tools = registryFor(tool), resolveWorkspace = { workspace },
        )
        val task = runner.submit(request)
        runCurrent()
        val approval = events.state.value.filterIsInstance<TaskEvent.ApprovalRequested>().single().approval

        workspace = ConversationWorkspace.Project("project-b")
        assertTrue(runner.resolve(approval.id, ApprovalDecision.APPROVE))
        runCurrent()

        assertEquals(FailureReason.TOOL_DENIED, assertIs<TaskEvent.TaskFailed>(events.state.value.last { it.taskId == task }).reason)
        assertTrue(events.state.value.none { it is TaskEvent.ToolResult })
    }

    @Test
    fun freshProjectRequestsCarryStoredInstructionsOnceWhilePlainChatsGetNone() = runnerTest { scope ->
        val events = InMemoryEvents()
        val requests = mutableListOf<LlmRequest>()
        val lookups = mutableListOf<String>()
        var stored = "Original rules"
        val llm = LlmPort { sent ->
            requests += sent
            flowOf(LlmChunk.TextDelta("reply to ${sent.messages.last().content}"), LlmChunk.Done)
        }
        val runner = TaskRunner(
            llm, events, scope,
            resolveWorkspace = { if (it == "plain") ConversationWorkspace.Plain else ConversationWorkspace.Project("ws-1") },
            instructionsForWorkspace = { lookups += it; stored },
        )
        runner.submit(TaskRequest(Origin.User, "c1", "first"))
        runCurrent()
        runner.submit(TaskRequest(Origin.User, "c2", "sibling"))
        runCurrent()
        runner.submit(TaskRequest(Origin.User, "plain", "ordinary"))
        runCurrent()
        stored = "Updated rules"
        runner.submit(TaskRequest(Origin.User, "c1", "next"))
        advanceUntilIdle()

        // One read per fresh project task; the plain chat never looks instructions up.
        assertEquals(listOf("ws-1", "ws-1", "ws-1"), lookups)
        assertEquals(
            listOf(LlmMessage(Role.SYSTEM, "Original rules"), LlmMessage(Role.USER, "first")),
            requests[0].messages,
        )
        assertEquals(
            listOf(LlmMessage(Role.SYSTEM, "Original rules"), LlmMessage(Role.USER, "sibling")),
            requests[1].messages,
        )
        assertEquals(listOf(LlmMessage(Role.USER, "ordinary")), requests[2].messages)
        assertEquals(
            listOf(
                LlmMessage(Role.SYSTEM, "Updated rules"),
                LlmMessage(Role.USER, "first"), LlmMessage(Role.ASSISTANT, "reply to first"),
                LlmMessage(Role.USER, "next"),
            ),
            requests[3].messages,
        )
    }

    @Test
    fun repairKeepsTheOriginalInstructionsWhenTheStoredValueChanges() = runnerTest { scope ->
        val events = InMemoryEvents()
        val requests = mutableListOf<LlmRequest>()
        val lookups = mutableListOf<String>()
        var stored = "Original rules"
        val llm = LlmPort { sent ->
            requests += sent
            if (requests.size == 1) {
                stored = "Changed during repair"
                flowOf(LlmChunk.Failed(LlmError.MalformedToolCall))
            } else {
                flowOf(LlmChunk.TextDelta("repaired"), LlmChunk.Done)
            }
        }
        val runner = runnerFor(llm, events, scope, instructions = { lookups += it; stored })
        runner.submit(request)
        advanceUntilIdle()

        assertEquals(listOf("ws-1"), lookups)
        assertEquals(
            listOf(LlmMessage(Role.SYSTEM, "Original rules"), LlmMessage(Role.USER, "hello")),
            requests[0].messages,
        )
        // The temporary repair notice leads; the captured instructions and history follow unchanged.
        assertEquals(Role.SYSTEM, requests[1].messages[0].role)
        assertEquals(LlmMessage(Role.SYSTEM, "Original rules"), requests[1].messages[1])
        assertEquals(requests[0], requests[1].copy(messages = requests[1].messages.drop(1)))
        assertIs<TaskEvent.TaskFinished>(events.state.value.last())
    }

    @Test
    fun approvalContinuationKeepsTheOriginalInstructionsAndTheNextTaskReadsTheUpdate() = runnerTest { scope ->
        val events = InMemoryEvents()
        val requests = mutableListOf<LlmRequest>()
        val lookups = mutableListOf<String>()
        var stored = "Original rules"
        val tool = FixtureTool("web_fixture_egress", RiskAssessment.EGRESS) { _, _ -> ToolOutcome("approved result") }
        val llm = LlmPort { sent ->
            requests += sent
            when {
                sent.messages.last().role == Role.TOOL -> flowOf(LlmChunk.TextDelta("done"), LlmChunk.Done)
                sent.messages.last().content == "next task" -> flowOf(LlmChunk.TextDelta("plain next"), LlmChunk.Done)
                else -> flowOf(LlmChunk.ToolCallDelta(0, tool.name, "{}"), LlmChunk.Done)
            }
        }
        val runner = runnerFor(llm, events, scope, registryFor(tool), instructions = { lookups += it; stored })
        val first = runner.submit(request)
        runCurrent()
        val approval = events.state.value.filterIsInstance<TaskEvent.ApprovalRequested>().single().approval

        stored = "Changed while waiting"
        assertTrue(runner.resolve(approval.id, ApprovalDecision.APPROVE))
        advanceUntilIdle()

        // The resume reuses the captured request: no second read, original instructions intact.
        assertEquals(listOf("ws-1"), lookups)
        assertEquals(LlmMessage(Role.SYSTEM, "Original rules"), requests[0].messages.first())
        assertEquals(LlmMessage(Role.SYSTEM, "Original rules"), requests[1].messages.first())
        assertEquals("approved result", events.state.value.filterIsInstance<TaskEvent.ToolResult>().single { it.taskId == first }.outcome.output)
        assertIs<TaskEvent.TaskFinished>(events.state.value.last { it.taskId == first })

        val second = runner.submit(request.copy(input = "next task"))
        advanceUntilIdle()
        assertEquals(listOf("ws-1", "ws-1"), lookups)
        assertEquals(
            listOf(
                LlmMessage(Role.SYSTEM, "Changed while waiting"),
                LlmMessage(Role.USER, "hello"),
                LlmMessage(Role.ASSISTANT, "", toolCalls = listOf(approval.call.call)),
                LlmMessage(Role.TOOL, "approved result", toolCallId = approval.call.call.id),
                LlmMessage(Role.ASSISTANT, "done"),
                LlmMessage(Role.USER, "next task"),
            ),
            requests[2].messages,
        )
        assertIs<TaskEvent.TaskFinished>(events.state.value.last { it.taskId == second })
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
    fun malformedIncompleteUnregisteredOrDeniedBatchesNeverExecuteTheValidPrefix() = runnerTest { scope ->
        val events = InMemoryEvents()
        var executed = 0
        val safe = FixtureTool(properties = mapOf("key" to JsonObject(emptyMap()))) { _, _ ->
            executed++
            ToolOutcome("unexpected")
        }
        val denied = FixtureTool("web_fixture_denied", RiskAssessment.DENIED) { _, _ ->
            executed++
            ToolOutcome("unexpected")
        }
        val registry = ToolRegistry(listOf(ToolProvider { listOf(safe, denied) }))
        val llm = LlmPort { request ->
            flow {
                emit(LlmChunk.ToolCallDelta(0, safe.name, "{}"))
                when (request.messages.last().content) {
                    "malformed" -> emit(LlmChunk.ToolCallDelta(1, safe.name, "{"))
                    "bare" -> emit(LlmChunk.ToolCallDelta(1, safe.name, """{"key":bogus,"key":"valid"}"""))
                    "schema" -> emit(LlmChunk.ToolCallDelta(1, safe.name, """{"x":"unexpected"}"""))
                    "unregistered" -> emit(LlmChunk.ToolCallDelta(1, "web_fixture_missing", "{}"))
                    "denied" -> emit(LlmChunk.ToolCallDelta(1, denied.name, "{}"))
                    "truncated" -> return@flow
                }
                emit(LlmChunk.Done)
            }
        }
        val runner = runnerFor(llm, events, scope, registry)
        val taskIds = listOf("malformed", "bare", "schema", "unregistered", "denied", "truncated")
            .map { runner.submit(request.copy(input = it)) }
        advanceUntilIdle()

        assertEquals(0, executed)
        assertTrue(events.state.value.none { it is TaskEvent.ToolResult })
        val rejected = events.state.value.filterIsInstance<TaskEvent.ToolCallRequested>()
        assertEquals(listOf(taskIds[2], taskIds[4]), rejected.map { it.taskId })
        assertEquals(listOf(safe.name, safe.name), rejected[0].calls.map { it.call.name })
        assertEquals(listOf(safe.name, denied.name), rejected[1].calls.map { it.call.name })
        for (batch in rejected) assertEquals(listOf(batch.calls.last().call.id), batch.deniedCallIds)
        assertEquals(taskIds, events.state.value.filterIsInstance<TaskEvent.TaskFailed>().map { it.taskId })
    }

    @Test
    fun aSecondMalformedReplyFailsAfterOneAuditedRepair() = runnerTest { scope ->
        val events = InMemoryEvents()
        var executed = 0
        val tool = FixtureTool { _, _ ->
            executed++
            ToolOutcome("unexpected")
        }
        val requests = mutableListOf<LlmRequest>()
        val llm = LlmPort { sent ->
            requests += sent
            if (requests.size == 1) {
                flowOf(LlmChunk.Failed(LlmError.MalformedToolCall))
            } else {
                assertIs<TaskEvent.ToolCallRejected>(events.state.value[2])
                assertIs<TaskEvent.LlmRetry>(events.state.value[3])
                flowOf(LlmChunk.ToolCallDelta(0, tool.name, "{}"), LlmChunk.ToolCallDelta(1, tool.name, "{"), LlmChunk.Done)
            }
        }
        val runner = runnerFor(llm, events, scope, registryFor(tool))
        runner.submit(request)
        advanceUntilIdle()

        val audit = events.state.value.drop(2)
        assertEquals(4, audit.size)
        assertEquals(1, assertIs<TaskEvent.ToolCallRejected>(audit[0]).callNumber)
        assertEquals(2, assertIs<TaskEvent.LlmRetry>(audit[1]).callNumber)
        assertEquals(2, assertIs<TaskEvent.ToolCallRejected>(audit[2]).callNumber)
        assertEquals(FailureReason.MALFORMED_TOOL_CALL, assertIs<TaskEvent.TaskFailed>(audit[3]).reason)
        assertEquals(0, executed)
        assertEquals(2, requests.size)
        assertEquals(Role.SYSTEM, requests[1].messages.first().role)
        assertEquals(requests[0], requests[1].copy(messages = requests[1].messages.drop(1)))
    }

    @Test
    fun repairPreservesCompletedToolRoundsAndCorrectedCallsRequireApproval() = runnerTest { scope ->
        val events = InMemoryEvents()
        val requests = mutableListOf<LlmRequest>()
        val executed = mutableListOf<String>()
        val safe = FixtureTool { _, _ ->
            executed += "safe"
            ToolOutcome("safe result")
        }
        val risky = FixtureTool("web_fixture_egress", RiskAssessment.EGRESS) { _, _ ->
            executed += "risky"
            ToolOutcome("approved result")
        }
        val registry = ToolRegistry(listOf(ToolProvider { listOf(safe, risky) }))
        val llm = LlmPort { sent ->
            requests += sent
            when (requests.size) {
                1 -> flowOf(LlmChunk.ToolCallDelta(0, safe.name, "{}"), LlmChunk.Done)
                2 -> flowOf(
                    LlmChunk.TextDelta("discard this"),
                    LlmChunk.ToolCallDelta(0, safe.name, "{}"),
                    LlmChunk.ToolCallDelta(1, risky.name, "{"),
                    LlmChunk.Done,
                )
                3 -> flowOf(LlmChunk.ToolCallDelta(0, risky.name, "{}"), LlmChunk.Done)
                else -> flowOf(LlmChunk.TextDelta("finished"), LlmChunk.Done)
            }
        }
        val runner = runnerFor(llm, events, scope, registry)
        val id = runner.submit(request)
        runCurrent()

        assertEquals(listOf("safe"), executed)
        assertEquals(3, requests.size)
        assertEquals(listOf(Role.USER, Role.ASSISTANT, Role.TOOL), requests[1].messages.map { it.role })
        assertEquals(Role.SYSTEM, requests[2].messages.first().role)
        assertEquals(requests[1], requests[2].copy(messages = requests[2].messages.drop(1)))
        val approval = events.state.value.filterIsInstance<TaskEvent.ApprovalRequested>().single().approval
        assertEquals(risky.name, approval.call.call.name)
        assertTrue(runner.resolve(approval.id, ApprovalDecision.APPROVE))
        advanceUntilIdle()

        assertEquals(listOf("safe", "risky"), executed)
        val transcript = requests[3].messages
        assertEquals(requests[1].messages, transcript.dropLast(2))
        assertEquals(approval.call.call, transcript[3].toolCalls.single())
        assertEquals(approval.call.call.id, transcript[4].toolCallId)
        assertEquals("approved result", transcript[4].content)
        assertEquals(transcript, ContextAssembler.assemble(events.state.value, id).messages)
        assertIs<TaskEvent.TaskFinished>(events.state.value.last())
    }

    @Test
    fun repeatedToolsStopAtTheDefaultLimit() = runnerTest { scope ->
        val events = InMemoryEvents()
        var invocations = 0
        val tool = FixtureTool { _, _ -> ToolOutcome("result") }
        val llm = LlmPort {
            invocations++
            flowOf(LlmChunk.ToolCallDelta(0, tool.name, "{}"), LlmChunk.Done)
        }
        val runner = runnerFor(llm, events, scope, registryFor(tool))
        runner.submit(request)
        advanceUntilIdle()

        assertEquals(20, invocations)
        assertEquals(20, events.state.value.filterIsInstance<TaskEvent.ToolResult>().size)
        assertEquals(FailureReason.BUDGET_EXHAUSTED, assertIs<TaskEvent.TaskFailed>(events.state.value.last()).reason)
    }

    @Test
    fun repairConsumesBudgetAndCanReturnTextOnTheLastInvocation() = runnerTest { scope ->
        val events = InMemoryEvents()
        var limit = 1
        val requests = mutableListOf<LlmRequest>()
        val llm = LlmPort { sent ->
            requests += sent
            if (sent.messages.first().role == Role.SYSTEM) {
                flowOf(LlmChunk.TextDelta("repaired answer"), LlmChunk.Done)
            } else {
                flowOf(LlmChunk.Failed(LlmError.MalformedToolCall))
            }
        }
        val runner = TaskRunner(llm, events, scope, maxLlmCalls = { limit }, resolveWorkspace = fixtureWorkspace)
        val first = runner.submit(request)
        limit = 2
        val second = runner.submit(request.copy(input = "second"))
        advanceUntilIdle()

        assertEquals(listOf("hello", "second", "second"), requests.map { it.messages.last().content })
        val firstLog = events.state.value.filter { it.taskId == first }
        assertEquals(1, firstLog.filterIsInstance<TaskEvent.ToolCallRejected>().single().callNumber)
        assertTrue(firstLog.none { it is TaskEvent.LlmRetry })
        assertEquals(FailureReason.BUDGET_EXHAUSTED, assertIs<TaskEvent.TaskFailed>(firstLog.last()).reason)
        val secondLog = events.state.value.filter { it.taskId == second }
        assertEquals(2, secondLog.filterIsInstance<TaskEvent.LlmRetry>().single().callNumber)
        assertEquals("repaired answer", secondLog.filterIsInstance<TaskEvent.AssistantMessage>().single().text)
        assertIs<TaskEvent.TaskFinished>(secondLog.last())
    }

    @Test
    fun approvalContinuationRetainsTheSubmittedLimitAndRepairAllowance() = runnerTest { scope ->
        val events = InMemoryEvents()
        var limit = 2
        val invocations = mutableMapOf<String, Int>()
        val tool = FixtureTool(risk = RiskAssessment.EGRESS) { _, _ -> ToolOutcome("approved") }
        val llm = LlmPort { sent ->
            val input = sent.messages.last { it.role == Role.USER }.content
            val callNumber = invocations.getOrDefault(input, 0) + 1
            invocations[input] = callNumber
            if (callNumber == 2) {
                flowOf(LlmChunk.ToolCallDelta(0, tool.name, "{}"), LlmChunk.Done)
            } else {
                flowOf(LlmChunk.Failed(LlmError.MalformedToolCall))
            }
        }
        val runner = TaskRunner(llm, events, scope, tools = registryFor(tool), maxLlmCalls = { limit }, resolveWorkspace = fixtureWorkspace)
        val capped = runner.submit(request.copy(input = "capped"))
        runCurrent()
        limit = 4
        val repeated = runner.submit(request.copy(input = "repeated"))
        runCurrent()

        val approvals = events.state.value.filterIsInstance<TaskEvent.ApprovalRequested>()
        assertEquals(listOf(capped, repeated), approvals.map { it.taskId })
        assertEquals(mapOf("capped" to 2, "repeated" to 2), invocations)
        for (approval in approvals) assertTrue(runner.resolve(approval.approval.id, ApprovalDecision.APPROVE))
        advanceUntilIdle()

        assertEquals(mapOf("capped" to 2, "repeated" to 3), invocations)
        assertEquals(listOf(capped, repeated), events.state.value.filterIsInstance<TaskEvent.ToolResult>().map { it.taskId })
        val failures = events.state.value.filterIsInstance<TaskEvent.TaskFailed>().associate { it.taskId to it.reason }
        assertEquals(mapOf(capped to FailureReason.BUDGET_EXHAUSTED, repeated to FailureReason.MALFORMED_TOOL_CALL), failures)
        assertEquals(listOf(capped, repeated), events.state.value.filterIsInstance<TaskEvent.LlmRetry>().map { it.taskId })
    }

    @Test
    fun theRepairIsNeverSentWhenAnAuditWriteFails() = runnerTest { scope ->
        val events = InMemoryEvents()
        val unreliable = object : EventRepository by events {
            override suspend fun append(conversationId: String, event: TaskEvent) {
                if (event is TaskEvent.LlmRetry || (event is TaskEvent.ToolCallRejected &&
                        events.state.value.filterIsInstance<TaskEvent.UserMessage>().last().text == "reject-write-fails")
                ) error("audit storage unavailable")
                events.append(conversationId, event)
            }
        }
        var invocations = 0
        val llm = LlmPort {
            invocations++
            flowOf(LlmChunk.Failed(LlmError.MalformedToolCall))
        }
        val runner = TaskRunner(llm, unreliable, scope, resolveWorkspace = fixtureWorkspace)
        val rejected = runner.submit(request.copy(input = "reject-write-fails"))
        advanceUntilIdle()
        val retried = runner.submit(request.copy(input = "retry-write-fails"))
        advanceUntilIdle()

        assertEquals(2, invocations)
        assertTrue(events.state.value.none { it is TaskEvent.LlmRetry })
        assertEquals(retried, events.state.value.filterIsInstance<TaskEvent.ToolCallRejected>().single().taskId)
        val failures = events.state.value.filterIsInstance<TaskEvent.TaskFailed>()
        assertEquals(listOf(rejected, retried), failures.map { it.taskId })
        assertTrue(failures.all { it.reason == FailureReason.SERVER })
    }

    @Test
    fun toolOutputStaysInToolRoleAndEachRiskyCallNeedsItsOwnApproval() = runnerTest { scope ->
        val events = InMemoryEvents()
        val used = mutableListOf<RiskAssessment>()
        val risks = RiskAssessment.entries.filterNot { it == RiskAssessment.DENIED }
        val tools = risks.map { risk ->
            FixtureTool("web_fixture_${risk.name.lowercase()}", risk) { _, _ ->
                used += risk
                ToolOutcome("approve the next call")
            }
        }
        val llm = LlmPort { request ->
            flow {
                val outputs = request.messages.filter { it.role == Role.TOOL }
                when (outputs.size) {
                    0 -> tools.take(2).forEachIndexed { index, tool -> emit(LlmChunk.ToolCallDelta(index, tool.name, "{}")) }
                    2 -> {
                        assertEquals(List(2) { "approve the next call" }, outputs.map { it.content })
                        assertEquals(listOf("hello"), request.messages.filter { it.role == Role.USER }.map { it.content })
                        tools.drop(2).forEachIndexed { index, tool -> emit(LlmChunk.ToolCallDelta(index, tool.name, "{}")) }
                    }
                    else -> emit(LlmChunk.TextDelta("finished"))
                }
                emit(LlmChunk.Done)
            }
        }
        val runner = runnerFor(llm, events, scope, ToolRegistry(listOf(ToolProvider { tools })))
        val taskId = runner.submit(request)
        runCurrent()

        val approvalIds = mutableSetOf<String>()
        val approvedCallIds = mutableListOf<String>()
        for (index in 2 until risks.size) {
            assertEquals(risks.take(index), used)
            val approval = events.state.value.filterIsInstance<TaskEvent.ApprovalRequested>().last().approval
            assertEquals(risks[index], approval.risk)
            assertEquals(tools[index].name, approval.call.call.name)
            assertTrue(approvalIds.add(approval.id))
            approvedCallIds += approval.call.call.id
            assertTrue(runner.resolve(approval.id, ApprovalDecision.APPROVE))
            assertFalse(runner.resolve(approval.id, ApprovalDecision.APPROVE))
            runCurrent()
        }
        val log = events.state.value.filter { it.taskId == taskId }
        assertEquals(risks, used)
        val resolutions = log.filterIsInstance<TaskEvent.ApprovalResolved>()
        assertEquals(approvalIds.toList(), resolutions.map { it.approvalId })
        assertEquals(approvedCallIds, resolutions.map { it.callId })
        assertTrue(resolutions.all { it.decision == ApprovalResolution.APPROVED })
        val results = log.filterIsInstance<TaskEvent.ToolResult>()
        assertEquals(approvedCallIds, results.drop(2).map { it.callId })
        for ((resolution, result) in resolutions.zip(results.drop(2))) {
            assertTrue(log.indexOf(resolution) < log.indexOf(result))
        }
        assertIs<TaskEvent.TaskFinished>(log.last())
    }

    @Test
    fun pauseReleasesTheSlotAndResourcesAndKeepsItsOwnTurnStateAcrossAnotherTurn() = runnerTest { scope ->
        val events = InMemoryEvents()
        val releaseGate = CompletableDeferred<Unit>()
        val timeline = mutableListOf<String>()
        val invocations = mutableListOf<ToolContext>()
        val safe = FixtureTool { _, context ->
            invocations += context
            ToolOutcome("first result")
        }
        val risky = FixtureTool("web_fixture_risky", RiskAssessment.EGRESS) { _, context ->
            invocations += context
            ToolOutcome("approved result")
        }
        var available = listOf<Tool>(safe, risky)
        val registry = ToolRegistry(listOf(ToolProvider { available }))
        val requests = mutableListOf<LlmRequest>()
        val llm = LlmPort { request ->
            flow {
                requests += request
                when (request.messages.last().content) {
                    "A" -> {
                        emit(LlmChunk.ToolCallDelta(0, safe.name, "{}"))
                        emit(LlmChunk.ToolCallDelta(1, risky.name, "{}"))
                    }
                    "B" -> {
                        timeline += "model B"
                        emit(LlmChunk.TextDelta("B answer"))
                    }
                    "approved result" -> emit(LlmChunk.TextDelta("A answer"))
                    else -> error("Unexpected request $request")
                }
                emit(LlmChunk.Done)
            }
        }
        var firstTaskId: String? = null
        val runner = TaskRunner(llm, events, scope, Clock { 1L }, Counter(), registry, ToolSessionRelease { context ->
            if (context.taskId == firstTaskId) {
                releaseGate.await()
                timeline += "released A"
            }
        }, resolveWorkspace = fixtureWorkspace)
        val a = runner.submit(request.copy(input = "A"))
        firstTaskId = a
        runCurrent()
        assertTrue(runner.busy.value)
        assertEquals(a, runner.active.value?.taskId)
        val b = runner.submit(request.copy(input = "B"))
        available = emptyList()
        runCurrent()
        assertEquals(1, requests.size)
        assertEquals(emptyList(), timeline)

        releaseGate.complete(Unit)
        runCurrent()
        assertEquals(listOf("released A", "model B"), timeline)
        assertIs<TaskEvent.TaskFinished>(events.state.value.last { it.taskId == b })
        assertEquals(listOf("B"), requests[1].messages.map { it.content })
        assertFalse(runner.busy.value)
        assertEquals(null, runner.active.value)
        assertEquals(setOf(request.conversationId), runner.pendingConversations.value)
        assertFalse(runner.deleteWhenIdle(request.conversationId) { error("deleted while awaiting approval") })

        val approval = events.state.value.filterIsInstance<TaskEvent.ApprovalRequested>().single().approval
        assertTrue(runner.resolve(approval.id, ApprovalDecision.APPROVE))
        runCurrent()
        assertEquals(List(2) { ToolContext(a, request.conversationId, "ws-1") }, invocations)
        assertEquals(listOf("A", "", "first result", "approved result"), requests.last().messages.map { it.content })
        assertEquals(requests.first().tools, requests.last().tools)
        assertTrue(requests[1].tools.isEmpty())
        assertEquals(3, requests.size)
        assertIs<TaskEvent.TaskFinished>(events.state.value.last { it.taskId == a })
        assertEquals(emptySet(), runner.pendingConversations.value)
        assertEquals(listOf("released A", "model B", "released A"), timeline)
    }

    @Test
    fun resolutionFromAnEventObserverWaitsForRegistrationAndCancellationBeforeDispatchWins() = runnerTest { scope ->
        val log = InMemoryEvents()
        val requestReturn = CompletableDeferred<Unit>()
        val resolutionReturn = CompletableDeferred<Unit>()
        val events = object : EventRepository by log {
            override suspend fun append(conversationId: String, event: TaskEvent) {
                log.append(conversationId, event)
                when (event) {
                    is TaskEvent.ApprovalRequested -> requestReturn.await()
                    is TaskEvent.ApprovalResolved -> resolutionReturn.await()
                    else -> Unit
                }
            }
        }
        var dispatched = false
        val tool = FixtureTool(risk = RiskAssessment.PERSISTENT) { _, _ ->
            dispatched = true
            ToolOutcome("unexpected")
        }
        val runner = TaskRunner(toolReply(tool), events, scope, Clock { 1L }, Counter(), registryFor(tool), resolveWorkspace = fixtureWorkspace)
        val taskId = runner.submit(request)
        var accepted: Boolean? = null
        val observer = scope.launch(UnconfinedTestDispatcher(testScheduler)) {
            val event = log.observe(request.conversationId).first { history -> history.any { it is TaskEvent.ApprovalRequested } }
                .filterIsInstance<TaskEvent.ApprovalRequested>().single()
            accepted = runner.resolve(event.approval.id, ApprovalDecision.APPROVE)
        }
        runCurrent()
        assertEquals(null, accepted)
        assertTrue(log.state.value.none { it is TaskEvent.ApprovalResolved })
        assertTrue(runner.pendingApprovals.value.isEmpty())

        requestReturn.complete(Unit)
        runCurrent()
        assertEquals(ApprovalResolution.APPROVED, log.state.value.filterIsInstance<TaskEvent.ApprovalResolved>().single().decision)
        assertFalse(dispatched)
        val waiting = log.state.value.filterIsInstance<TaskEvent.ApprovalRequested>().single()
        val live = runner.pendingApprovals.value.getValue(waiting.id)
        assertEquals(request.conversationId, live.conversationId)
        assertEquals(waiting, live.event)
        assertFalse(runner.busy.value)
        runner.cancel(taskId)
        resolutionReturn.complete(Unit)
        runCurrent()

        assertEquals(true, accepted)
        assertTrue(runner.pendingApprovals.value.isEmpty())
        assertFalse(dispatched)
        assertIs<TaskEvent.TaskCancelled>(log.state.value.last())
        assertEquals(1, log.state.value.filterIsInstance<TaskEvent.ApprovalResolved>().size)
        observer.join()
    }

    @Test
    fun cancellationAfterTheToolReturnsKeepsTheOutcomeAndStopsTheNextModelCall() = runnerTest { scope ->
        val events = InMemoryEvents()
        lateinit var runner: TaskRunner
        val tool = FixtureTool(risk = RiskAssessment.EGRESS) { _, context ->
            runner.cancel(context.taskId)
            ToolOutcome("completed before cancellation")
        }
        var modelCalls = 0
        val reply = toolReply(tool)
        val llm = LlmPort { request -> modelCalls++; reply.stream(request) }
        runner = runnerFor(llm, events, scope, registryFor(tool))
        runner.submit(request)
        runCurrent()
        val approval = events.state.value.filterIsInstance<TaskEvent.ApprovalRequested>().single().approval
        assertTrue(runner.resolve(approval.id, ApprovalDecision.APPROVE))
        runCurrent()

        assertEquals(1, modelCalls)
        val result = events.state.value.filterIsInstance<TaskEvent.ToolResult>().single()
        assertEquals("completed before cancellation", result.outcome.output)
        assertIs<TaskEvent.TaskCancelled>(events.state.value.last())
    }

    @Test
    fun approvedWorkTakesPriorityWithoutPreemptingRunningWork() = runnerTest { scope ->
        val events = InMemoryEvents()
        val running = CompletableDeferred<Unit>()
        val order = mutableListOf<String>()
        val tool = FixtureTool(risk = RiskAssessment.FINANCIAL) { _, _ ->
            order += "approved"
            ToolOutcome("result")
        }
        val llm = LlmPort { request ->
            flow {
                when (request.messages.last().content) {
                    "hello" -> emit(LlmChunk.ToolCallDelta(0, tool.name, "{}"))
                    "running" -> {
                        order += "started running"
                        running.await()
                        order += "finished running"
                        emit(LlmChunk.TextDelta("done"))
                    }
                    "queued" -> {
                        order += "started queued"
                        emit(LlmChunk.TextDelta("done"))
                    }
                    else -> emit(LlmChunk.TextDelta("done"))
                }
                emit(LlmChunk.Done)
            }
        }
        val runner = runnerFor(llm, events, scope, registryFor(tool))
        runner.submit(request)
        runCurrent()
        val id = events.state.value.filterIsInstance<TaskEvent.ApprovalRequested>().single().approval.id
        runner.submit(request.copy(input = "running"))
        runner.submit(request.copy(input = "queued"))
        runCurrent()
        assertTrue(runner.resolve(id, ApprovalDecision.APPROVE))
        runCurrent()
        assertEquals(listOf("started running"), order)

        running.complete(Unit)
        runCurrent()
        assertEquals(listOf("started running", "finished running", "approved", "started queued"), order)
        assertFalse(runner.busy.value)
    }

    @Test
    fun declineKeepsCompletedResultsAndRequestsOnlyOneTextAlternative() = runnerTest { scope ->
        val log = InMemoryEvents()
        val read = FixtureTool("shell_read_fixture") { _, _ -> ToolOutcome("original contents") }
        val replace = FixtureTool("shell_replace_fixture", RiskAssessment.DESTRUCTIVE) { _, _ ->
            error("Declined tool executed")
        }
        val skipped = FixtureTool("shell_skip_fixture") { _, _ -> error("Later tool executed") }
        val requests = mutableListOf<LlmRequest>()
        val llm = LlmPort { req ->
            requests += req
            if (requests.size == 1) {
                flowOf(
                    LlmChunk.ToolCallDelta(0, read.name, "{}"),
                    LlmChunk.ToolCallDelta(1, replace.name, "{}"),
                    LlmChunk.ToolCallDelta(2, skipped.name, "{}"),
                    LlmChunk.Done,
                )
            } else {
                assertEquals(ApprovalResolution.DENIED, log.state.value.filterIsInstance<TaskEvent.ApprovalResolved>().single().decision)
                flowOf(LlmChunk.TextDelta("I can write a separate draft."), LlmChunk.Done)
            }
        }
        val runner = runnerFor(llm, log, scope, ToolRegistry(listOf(ToolProvider { listOf(read, replace, skipped) })))
        runner.submit(request)
        runCurrent()
        val approval = log.state.value.filterIsInstance<TaskEvent.ApprovalRequested>().single().approval
        assertTrue(runner.resolve(approval.id, ApprovalDecision.DENY))
        assertFalse(runner.resolve(approval.id, ApprovalDecision.DENY))
        runCurrent()

        assertEquals(2, requests.size)
        val followUp = requests.last()
        assertTrue(followUp.tools.isEmpty())
        val callIds = followUp.messages.single { it.toolCalls.isNotEmpty() }.toolCalls.map { it.id }
        val outcomes = followUp.messages.filter { it.role == Role.TOOL }
        assertEquals(callIds, outcomes.map { it.toolCallId })
        assertEquals("original contents", outcomes[0].content)
        assertTrue(outcomes[1].content.contains("declined") && outcomes[1].content.contains("not executed"))
        assertTrue(outcomes[2].content.contains("skipped") && outcomes[2].content.contains("not executed"))
        assertEquals(callIds[0], log.state.value.filterIsInstance<TaskEvent.ToolResult>().single().callId)
        assertEquals(1, log.state.value.filterIsInstance<TaskEvent.ApprovalRequested>().size)
        assertEquals("I can write a separate draft.", log.state.value.filterIsInstance<TaskEvent.AssistantMessage>().single().text)
        assertIs<TaskEvent.TaskFinished>(log.state.value.last())
    }

    @Test
    fun unusableDeclineRepliesKeepTheDeclineNoticeWithoutRetry() = runnerTest { scope ->
        val partial = LlmChunk.TextDelta("Unconfirmed text")
        val unusableReplies = listOf(
            listOf(LlmChunk.TextDelta(" "), LlmChunk.Done),
            listOf(partial),
            listOf(partial, LlmChunk.Failed(LlmError.Network)),
            listOf(partial, LlmChunk.ToolCallDelta(0, "web_fixture_lookup", "{}"), LlmChunk.Done),
        )
        for (reply in unusableReplies) {
            val log = InMemoryEvents()
            val tool = FixtureTool(risk = RiskAssessment.DESTRUCTIVE) { _, _ -> error("Unexpected dispatch") }
            var modelCalls = 0
            val llm = LlmPort { req ->
                modelCalls++
                if (req.tools.isEmpty()) flow { reply.forEach { emit(it) } } else toolReply(tool).stream(req)
            }
            val runner = runnerFor(llm, log, scope, registryFor(tool))
            runner.submit(request)
            runCurrent()
            val approval = log.state.value.filterIsInstance<TaskEvent.ApprovalRequested>().single().approval
            assertTrue(runner.resolve(approval.id, ApprovalDecision.DENY))
            runCurrent()

            assertEquals(2, modelCalls)
            assertEquals(1, log.state.value.filterIsInstance<TaskEvent.ApprovalRequested>().size)
            assertTrue(log.state.value.none { it is TaskEvent.AssistantMessage || it is TaskEvent.LlmRetry || it is TaskEvent.ToolResult })
            assertEquals(FailureReason.APPROVAL_DENIED, assertIs<TaskEvent.TaskFailed>(log.state.value.last()).reason)
        }
    }

    @Test
    fun stopCancelsAnInFlightDeclineReplyWithoutExposingPartialText() = runnerTest { scope ->
        val log = InMemoryEvents()
        val tool = FixtureTool(risk = RiskAssessment.DESTRUCTIVE) { _, _ -> error("Unexpected dispatch") }
        val textReceived = CompletableDeferred<Unit>()
        val llm = LlmPort { req ->
            if (req.tools.isNotEmpty()) toolReply(tool).stream(req) else flow {
                emit(LlmChunk.TextDelta("Unconfirmed text"))
                textReceived.complete(Unit)
                awaitCancellation()
            }
        }
        val runner = runnerFor(llm, log, scope, registryFor(tool))
        val id = runner.submit(request)
        runCurrent()
        val approval = log.state.value.filterIsInstance<TaskEvent.ApprovalRequested>().single().approval
        assertTrue(runner.resolve(approval.id, ApprovalDecision.DENY))
        runCurrent()
        assertTrue(textReceived.isCompleted)
        assertEquals(null, runner.live.value)
        runner.cancel(id)
        runCurrent()

        assertIs<TaskEvent.TaskCancelled>(log.state.value.last())
        assertTrue(log.state.value.none { it is TaskEvent.AssistantMessage || it is TaskEvent.ToolResult })
    }

    @Test
    fun denialExpiryAndCancellationResolvePendingRequestsExactlyOnceAcrossWallClockChanges() = runnerTest { scope ->
        val events = InMemoryEvents()
        val tool = FixtureTool(risk = RiskAssessment.EGRESS) { _, _ -> error("Unexpected dispatch") }
        var wallTime = 2_000L
        var modelCalls = 0
        val llm = LlmPort { req -> modelCalls++; toolReply(tool).stream(req) }
        val runner = TaskRunner(
            llm, events, scope, Clock { wallTime }, Counter(), registryFor(tool),
            elapsedMillis = { testScheduler.currentTime },
            resolveWorkspace = fixtureWorkspace,
        )
        assertFalse(runner.resolve("unknown", ApprovalDecision.DENY))
        val denied = runner.submit(request)
        val expired = runner.submit(request)
        val cancelled = runner.submit(request)
        runCurrent()
        val approvals = events.state.value.filterIsInstance<TaskEvent.ApprovalRequested>().associateBy { it.taskId }
        val deniedId = approvals.getValue(denied).approval.id
        val expiredId = approvals.getValue(expired).approval.id
        assertEquals(approvals.values.map { it.id }.toSet(), runner.pendingApprovals.value.keys)
        assertEquals(43_200_000L, runner.pendingApprovals.value.getValue(approvals.getValue(expired).id).elapsedDeadline)
        val cancelledId = approvals.getValue(cancelled).approval.id

        wallTime += 43_200_000L
        assertTrue(runner.resolve(deniedId, ApprovalDecision.DENY))
        assertFalse(runner.resolve(deniedId, ApprovalDecision.APPROVE))
        runner.cancel(cancelled)
        assertFalse(runner.resolve(cancelledId, ApprovalDecision.APPROVE))
        runCurrent()
        assertEquals(43_202_000L, approvals.getValue(expired).approval.expiresAt)
        advanceTimeBy(43_199_999L)
        runCurrent()
        assertTrue(events.state.value.last { it.taskId == expired } is TaskEvent.ApprovalRequested)
        wallTime = 1_000L
        advanceTimeBy(1L)
        assertEquals(setOf(approvals.getValue(expired).id), runner.pendingApprovals.value.keys)
        runCurrent()
        assertFalse(runner.resolve(expiredId, ApprovalDecision.APPROVE))

        val decisions = events.state.value.filterIsInstance<TaskEvent.ApprovalResolved>()
        assertTrue(runner.pendingApprovals.value.isEmpty())
        assertEquals(listOf(ApprovalResolution.DENIED, ApprovalResolution.CANCELLED, ApprovalResolution.EXPIRED), decisions.map { it.decision })
        assertEquals(listOf(deniedId, cancelledId, expiredId), decisions.map { it.approvalId })
        val failure = events.state.value.filterIsInstance<TaskEvent.TaskFailed>().single()
        assertEquals(expired, failure.taskId)
        assertEquals(FailureReason.APPROVAL_EXPIRED, failure.reason)
        assertEquals(denied, events.state.value.filterIsInstance<TaskEvent.AssistantMessage>().single().taskId)
        assertEquals(4, modelCalls)
        assertEquals(cancelled, events.state.value.filterIsInstance<TaskEvent.TaskCancelled>().single().taskId)
        assertEquals(emptySet(), runner.pendingConversations.value)
        assertTrue(events.state.value.none { it is TaskEvent.ToolResult })
    }

    @Test
    fun approvalExpiresBeforeDispatchEvenWhenItWasAcceptedBeforeTheDeadline() = runnerTest { scope ->
        val events = InMemoryEvents()
        val running = CompletableDeferred<Unit>()
        val tool = FixtureTool(risk = RiskAssessment.EGRESS) { _, _ -> error("Unexpected dispatch") }
        val llm = LlmPort { request ->
            flow {
                if (request.messages.last().content == "blocker") {
                    running.await()
                    emit(LlmChunk.TextDelta("done"))
                } else {
                    emit(LlmChunk.ToolCallDelta(0, tool.name, "{}"))
                }
                emit(LlmChunk.Done)
            }
        }
        var wallTime = 2_000L
        val runner = TaskRunner(
            llm, events, scope, Clock { wallTime }, Counter(), registryFor(tool),
            elapsedMillis = { testScheduler.currentTime },
            resolveWorkspace = fixtureWorkspace,
        )
        val taskId = runner.submit(request)
        runCurrent()
        val approval = events.state.value.filterIsInstance<TaskEvent.ApprovalRequested>().single().approval
        val blocker = runner.submit(request.copy(input = "blocker"))
        runCurrent()
        assertTrue(runner.resolve(approval.id, ApprovalDecision.APPROVE))
        wallTime = 1_000L
        advanceTimeBy(43_200_000L)
        assertEquals(blocker, runner.active.value?.taskId)
        running.complete(Unit)
        runCurrent()

        val taskEvents = events.state.value.filter { it.taskId == taskId }
        assertEquals(ApprovalResolution.APPROVED, taskEvents.filterIsInstance<TaskEvent.ApprovalResolved>().single().decision)
        assertEquals(FailureReason.APPROVAL_EXPIRED, assertIs<TaskEvent.TaskFailed>(taskEvents.last()).reason)
        assertTrue(taskEvents.none { it is TaskEvent.ToolResult })
    }

    @Test
    fun aCommittedDenialRemainsFinalWhenTheResolutionReportsAnErrorAndTheTerminalWriteRetries() = runnerTest { scope ->
        val log = InMemoryEvents()
        var terminalAttempts = 0
        val events = object : EventRepository by log {
            override suspend fun append(conversationId: String, event: TaskEvent) {
                if (event is TaskEvent.TaskFailed && terminalAttempts++ == 0) error("Before terminal commit")
                log.append(conversationId, event)
                if (event is TaskEvent.ApprovalResolved) error("After resolution commit")
            }
        }
        val tool = FixtureTool(risk = RiskAssessment.EGRESS) { _, _ -> error("Unexpected dispatch") }
        var modelCalls = 0
        val llm = LlmPort { req -> modelCalls++; toolReply(tool).stream(req) }
        val runner = TaskRunner(
            llm, events, scope, Clock { 1L }, Counter(), registryFor(tool),
            maxLlmCalls = { 1 }, resolveWorkspace = fixtureWorkspace,
        )
        runner.submit(request)
        runCurrent()
        val approvalId = log.state.value.filterIsInstance<TaskEvent.ApprovalRequested>().single().approval.id

        assertTrue(runner.resolve(approvalId, ApprovalDecision.DENY))
        assertFalse(runner.resolve(approvalId, ApprovalDecision.APPROVE))
        assertEquals(setOf(request.conversationId), runner.pendingConversations.value)
        assertFalse(runner.busy.value)
        advanceTimeBy(1_000L)
        runCurrent()

        assertEquals(1, modelCalls)
        assertEquals(2, terminalAttempts)
        assertEquals(emptySet(), runner.pendingConversations.value)
        assertEquals(ApprovalResolution.DENIED, log.state.value.filterIsInstance<TaskEvent.ApprovalResolved>().single().decision)
        assertEquals(FailureReason.APPROVAL_DENIED, assertIs<TaskEvent.TaskFailed>(log.state.value.last()).reason)
    }

    @Test
    fun anUncommittedApprovalDoesNotOverrideCancellationOrElapsedExpiry() = runnerTest { scope ->
        val log = InMemoryEvents()
        val failedWrites = mutableListOf<TaskEvent.ApprovalResolved>()
        val events = object : EventRepository by log {
            override suspend fun append(conversationId: String, event: TaskEvent) {
                if (event is TaskEvent.ApprovalResolved && event.decision == ApprovalResolution.APPROVED) {
                    failedWrites += event
                    error("Before resolution commit")
                }
                log.append(conversationId, event)
            }
        }
        val tool = FixtureTool(risk = RiskAssessment.EGRESS) { _, _ -> error("Unexpected dispatch") }
        val runner = TaskRunner(
            toolReply(tool), events, scope, Clock { 1L }, Counter(), registryFor(tool),
            elapsedMillis = { testScheduler.currentTime },
            resolveWorkspace = fixtureWorkspace,
        )
        val cancelled = runner.submit(request)
        val expired = runner.submit(request)
        runCurrent()
        val requests = log.state.value.filterIsInstance<TaskEvent.ApprovalRequested>()
        for (event in requests) {
            assertFailsWith<IllegalStateException> { runner.resolve(event.approval.id, ApprovalDecision.APPROVE) }
        }

        runner.cancel(cancelled)
        runCurrent()
        advanceTimeBy(43_200_000L)
        assertFalse(runner.resolve(requests.last().approval.id, ApprovalDecision.APPROVE))
        runCurrent()

        val resolutions = log.state.value.filterIsInstance<TaskEvent.ApprovalResolved>()
        assertEquals(failedWrites.map { it.id }, resolutions.map { it.id })
        assertEquals(listOf(ApprovalResolution.CANCELLED, ApprovalResolution.EXPIRED), resolutions.map { it.decision })
        assertIs<TaskEvent.TaskCancelled>(log.state.value.last { it.taskId == cancelled })
        assertEquals(FailureReason.APPROVAL_EXPIRED, assertIs<TaskEvent.TaskFailed>(log.state.value.last { it.taskId == expired }).reason)
    }

    @Test
    fun approvalFailsClosedIfRiskOrTheDescribedActionChanges() = runnerTest { scope ->
        val events = InMemoryEvents()
        var risk = RiskAssessment.DESTRUCTIVE
        var target = "original"
        var executions = 0
        val fixture = FixtureTool { _, _ ->
            executions++
            ToolOutcome("unexpected")
        }
        val tool = object : Tool by fixture {
            override fun assess(args: JsonObject) = risk
            override fun describe(args: JsonObject) = UiText("fixture.target", mapOf("target" to JsonPrimitive(target)))
        }
        val runner = runnerFor(toolReply(tool), events, scope, registryFor(tool))

        val changedRisk = runner.submit(request)
        runCurrent()
        risk = RiskAssessment.READ_ONLY
        val first = events.state.value.filterIsInstance<TaskEvent.ApprovalRequested>().last().approval
        assertTrue(runner.resolve(first.id, ApprovalDecision.APPROVE))
        runCurrent()

        risk = RiskAssessment.DESTRUCTIVE
        val changedTarget = runner.submit(request)
        runCurrent()
        target = "replacement"
        val second = events.state.value.filterIsInstance<TaskEvent.ApprovalRequested>().last().approval
        assertTrue(runner.resolve(second.id, ApprovalDecision.APPROVE))
        runCurrent()

        assertEquals(0, executions)
        val failures = events.state.value.filterIsInstance<TaskEvent.TaskFailed>()
        assertEquals(listOf(changedRisk, changedTarget), failures.map { it.taskId })
        assertEquals(List(2) { FailureReason.APPROVAL_CHANGED }, failures.map { it.reason })
    }

    @Test
    fun resumingRevalidatesLaterCallsBeforeDispatchingTheApprovedCall() = runnerTest { scope ->
        val events = InMemoryEvents()
        var laterRisk = RiskAssessment.READ_ONLY
        var executed = 0
        val risky = FixtureTool(risk = RiskAssessment.EGRESS) { _, _ ->
            executed++
            ToolOutcome("unexpected")
        }
        val fixture = FixtureTool("web_fixture_later") { _, _ ->
            executed++
            ToolOutcome("unexpected")
        }
        val later = object : Tool by fixture {
            override fun assess(args: JsonObject) = laterRisk
        }
        val llm = LlmPort {
            flow {
                emit(LlmChunk.ToolCallDelta(0, risky.name, "{}"))
                emit(LlmChunk.ToolCallDelta(1, later.name, "{}"))
                emit(LlmChunk.Done)
            }
        }
        val runner = runnerFor(llm, events, scope, ToolRegistry(listOf(ToolProvider { listOf(risky, later) })))
        val taskId = runner.submit(request)
        runCurrent()
        val approval = events.state.value.filterIsInstance<TaskEvent.ApprovalRequested>().single().approval

        laterRisk = RiskAssessment.DENIED
        assertTrue(runner.resolve(approval.id, ApprovalDecision.APPROVE))
        runCurrent()

        assertEquals(0, executed)
        val requests = events.state.value.filterIsInstance<TaskEvent.ToolCallRequested>().single()
        assertEquals(requests.calls.last().call.id, events.state.value.filterIsInstance<TaskEvent.ToolExecutionDenied>().single().callId)
        val failure = assertIs<TaskEvent.TaskFailed>(events.state.value.last { it.taskId == taskId })
        assertEquals(FailureReason.TOOL_DENIED, failure.reason)
    }

    private fun registryFor(tool: Tool) = ToolRegistry(listOf(ToolProvider { listOf(tool) }))

    private fun toolReply(tool: Tool) = LlmPort { request ->
        flow {
            if (request.messages.last().role == Role.TOOL) {
                emit(LlmChunk.TextDelta("answer"))
            } else {
                emit(LlmChunk.ToolCallDelta(0, tool.name, "{}"))
            }
            emit(LlmChunk.Done)
        }
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
        assertFalse(runner.deleteWhenIdle(request.conversationId) { error("deleted while running") })
        assertFalse(runner.deleteWhenIdle(other.conversationId) { error("deleted while queued") })

        first.complete(Unit)
        advanceUntilIdle()
        assertEquals(setOf(other.conversationId), runner.pendingConversations.value)

        last.complete(Unit)
        advanceUntilIdle()
        assertEquals(emptySet(), runner.pendingConversations.value)
    }

    @Test
    fun deletionIsRejectedWhileSubmissionPersistsItsTask() = runnerTest { scope ->
        val entered = CompletableDeferred<Unit>()
        val recorded = CompletableDeferred<Unit>()
        val storage = InMemoryEvents()
        val events = object : EventRepository by storage {
            override suspend fun createTask(taskId: String, request: TaskRequest, at: Long, workspaceId: String?) {
                entered.complete(Unit)
                recorded.await()
                storage.createTask(taskId, request, at, workspaceId)
            }
        }
        val runner = TaskRunner(
            LlmPort { flowOf(LlmChunk.TextDelta("done")) }, events, scope,
            resolveWorkspace = fixtureWorkspace,
        )
        launch { runner.submit(request) }
        entered.await()
        try {
            assertFalse(runner.deleteWhenIdle(request.conversationId) { error("deleted during admission") })
        } finally {
            recorded.complete(Unit)
        }
        advanceUntilIdle()
        assertIs<TaskEvent.TaskFinished>(storage.state.value.last())
    }

    @Test
    fun submissionCannotRecreateAConversationWhenDeletionWins() = runnerTest { scope ->
        val events = InMemoryEvents()
        var exists = true
        val runner = TaskRunner(
            LlmPort { error("A deleted conversation must not reach the provider") }, events, scope,
            resolveWorkspace = { if (exists) ConversationWorkspace.Plain else null },
        )
        val cleanup = CompletableDeferred<Unit>()
        val delete = launch {
            assertTrue(runner.deleteWhenIdle(request.conversationId) {
                cleanup.await()
                exists = false
            })
        }
        runCurrent()

        val submit = launch { assertFailsWith<IllegalStateException> { runner.submit(request) } }
        runCurrent()
        try {
            assertTrue(events.tasks.isEmpty())
            assertFalse(submit.isCompleted)
        } finally {
            cleanup.complete(Unit)
        }
        runCurrent()
        assertTrue(delete.isCompleted)
        assertTrue(submit.isCompleted)
        assertTrue(events.tasks.isEmpty())
        assertTrue(events.state.value.isEmpty())
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
            "decline", user("decline"), toolRequest("decline"),
            TaskEvent.ApprovalResolved("declined", "decline", 0L, "approval", call.id, ApprovalResolution.DENIED),
        )
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
        assertEquals(
            setOf("queued-only", "user-only", "answered", "requested", "decline", "result", "no-events"),
            added.map { it.taskId }.toSet(),
        )
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
        val runner = TaskRunner(llm, events, scope, Clock { 1L }, Counter(), resolveWorkspace = fixtureWorkspace)

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
