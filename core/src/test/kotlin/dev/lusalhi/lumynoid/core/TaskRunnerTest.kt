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

    /** Same rule as `:data`: no event, or a last event of Queued, UserMessage or AssistantMessage. */
    override suspend fun unfinishedTasks(): List<UnfinishedTask> = conversations.mapNotNull { (taskId, conv) ->
        val last = state.value.lastOrNull { it.taskId == taskId }
        val open = last == null || last is TaskEvent.TaskQueued || last is TaskEvent.UserMessage || last is TaskEvent.AssistantMessage
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

@OptIn(ExperimentalCoroutinesApi::class)
class TaskRunnerTest {
    private val request = TaskRequest(Origin.User, "conv-1", "ws-1", "hello")

    private fun runnerFor(
        llm: LlmPort,
        events: InMemoryEvents,
        scope: CoroutineScope,
    ) = TaskRunner(llm, events, scope, Clock { 1L }, Counter())

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
        events.seed("no-events")
        events.seed("done", queued("done"), user("done"), answer("done"), TaskEvent.TaskFinished("f-done", "done", 0L))
        events.seed("failed", user("failed"), TaskEvent.TaskFailed("x-failed", "failed", 0L, FailureReason.NETWORK))
        events.seed("stopped", user("stopped"), TaskEvent.TaskCancelled("c-stopped", "stopped", 0L))
        events.seed("unknown", user("unknown"), TaskEvent.Unknown("k", "unknown", 0L))
        val before = events.state.value.size
        runnerFor(LlmPort { flow { emit(LlmChunk.Done) } }, events, scope)

        advanceUntilIdle()

        val added = events.state.value.drop(before)
        assertTrue(added.all { it is TaskEvent.TaskFailed && it.reason == FailureReason.INTERRUPTED })
        assertEquals(setOf("queued-only", "user-only", "answered", "no-events"), added.map { it.taskId }.toSet())
        assertEquals(4, added.size)
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
