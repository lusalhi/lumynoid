package dev.lusalhi.lumynoid.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

private class InMemoryEvents : EventRepository {
    val state = MutableStateFlow<List<TaskEvent>>(emptyList())
    val tasks = mutableListOf<String>()
    override suspend fun createTask(taskId: String, request: TaskRequest, at: Long) { tasks += taskId }
    override suspend fun append(conversationId: String, event: TaskEvent) { state.value = state.value + event }
    override fun observe(conversationId: String): Flow<List<TaskEvent>> = state
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
                order += "start:${req.messages.single().content}"
                emit(LlmChunk.TextDelta("x"))
                order += "end:${req.messages.single().content}"
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
                emit(LlmChunk.TextDelta("fine:" + req.messages.single().content))
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
                val input = req.messages.single().content
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
    fun eventsRoundTripThroughJsonAndUnknownTypesDecodeToUnknown() {
        val original = TaskEvent.UserMessage("e1", "t1", 5L, Origin.User, "hi")
        val text = TaskEventJson.encode(original)
        assertTrue(text.contains("\"type\":\"user_message\""))
        assertEquals(original, TaskEventJson.decode(text))

        val future = TaskEventJson.decode("""{"type":"from_the_future","id":"e9","taskId":"t9","at":7,"extra":1}""")
        assertIs<TaskEvent.Unknown>(future)
    }
}
