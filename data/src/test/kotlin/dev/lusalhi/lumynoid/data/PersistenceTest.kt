package dev.lusalhi.lumynoid.data

import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import dev.lusalhi.lumynoid.core.ActiveModel
import dev.lusalhi.lumynoid.core.ApprovalDecision
import dev.lusalhi.lumynoid.core.ApprovalResolution
import dev.lusalhi.lumynoid.core.Clock
import dev.lusalhi.lumynoid.core.FailureReason
import dev.lusalhi.lumynoid.core.LlmPort
import dev.lusalhi.lumynoid.core.ModelProfile
import dev.lusalhi.lumynoid.core.Origin
import dev.lusalhi.lumynoid.core.ProviderConfig
import dev.lusalhi.lumynoid.core.ProviderKind
import dev.lusalhi.lumynoid.core.RiskAssessment
import dev.lusalhi.lumynoid.core.SecretRef
import dev.lusalhi.lumynoid.core.TaskEvent
import dev.lusalhi.lumynoid.core.TaskEventJson
import dev.lusalhi.lumynoid.core.TaskRequest
import dev.lusalhi.lumynoid.core.TaskRunner
import dev.lusalhi.lumynoid.core.ToolApproval
import dev.lusalhi.lumynoid.core.ToolCall
import dev.lusalhi.lumynoid.core.ToolOutcome
import dev.lusalhi.lumynoid.core.ToolProvider
import dev.lusalhi.lumynoid.core.ToolRegistry
import dev.lusalhi.lumynoid.core.UiText
import dev.lusalhi.lumynoid.core.UuidV7
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.RandomAccessFile
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Runs the real Room schema on a real SQLite file, closing and reopening it like a process restart. */
class PersistenceTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val blobRoot get() = File(folder.root, "event-blobs")

    private fun repository(dao: LumynoidDao): RoomEventRepository =
        RoomEventRepository(dao, EventBlobStore(blobRoot))

    private fun open(file: File): LumynoidDatabase =
        Room.databaseBuilder<LumynoidDatabase>(file.path) { LumynoidDatabase_Impl() }
            .setDriver(BundledSQLiteDriver())
            .build()

    @Test
    fun conversationAndEventsSurviveClosingAndReopeningTheDatabase() = runTest {
        val file = folder.newFile("lumynoid.db")
        val request: (String) -> TaskRequest = { TaskRequest(Origin.User, it, "ws", "hello") }

        val first = open(file)
        val conversation = RoomConversationPort(first.dao(), UuidV7, Clock { 10L }).create("hello")
        val events = repository(first.dao())
        events.createTask("t1", request(conversation.id), 10L)
        events.append(conversation.id, TaskEvent.UserMessage("e1", "t1", 10L, Origin.User, "hello"))
        events.append(conversation.id, TaskEvent.AssistantMessage("e2", "t1", 11L, "world"))
        events.append(conversation.id, TaskEvent.TaskFinished("e3", "t1", 12L))
        first.close()

        val second = open(file)
        val reopened = repository(second.dao()).observe(conversation.id).first()
        assertEquals(listOf("e1", "e2", "e3"), reopened.map { it.id })
        assertEquals("world", (reopened[1] as TaskEvent.AssistantMessage).text)
        assertEquals(conversation.id, RoomConversationPort(second.dao(), UuidV7, Clock { 20L }).latest()?.id)
        second.close()
    }

    @Test
    fun anUnreadableStoredEventBecomesUnknownInsteadOfCrashing() = runTest {
        val db = open(folder.newFile("lumynoid.db"))
        val dao = db.dao()
        dao.insertEvent(TaskEventEntity(id = "bad", taskId = "t", conversationId = "c", at = 1L, payload = "{not json"))
        val future = """{"type":"from_the_future","id":"new","taskId":"t","at":2}"""
        dao.insertEvent(TaskEventEntity(id = "new", taskId = "t", conversationId = "c", at = 2L, payload = future))

        val read = repository(dao).observe("c").first()

        assertEquals(2, read.size)
        read.forEach { assertIs<TaskEvent.Unknown>(it) }
        db.close()
    }

    @Test
    fun largeUtf8EventUsesAReferenceOnFirstInsertAndReadsFullyAfterReopen() = runTest {
        val file = folder.newFile("lumynoid.db")
        val first = open(file)
        val c = RoomConversationPort(first.dao(), UuidV7, Clock { 1L }).create("x")
        val events = repository(first.dao())
        events.createTask("t", TaskRequest(Origin.User, c.id, "ws", "x"), 1L)
        val call = ToolCall("call", "web_fixture_lookup", "{}")
        val requested = TaskEvent.ToolCallRequested(
            "request", "t", 2L, "", listOf(TaskEvent.RequestedToolCall(call, UiText("card.fixture.lookup"))),
        )
        val tail = "END OF FULL TOOL OUTPUT"
        val result = TaskEvent.ToolResult("result", "t", 3L, call.id, ToolOutcome("界".repeat(90_000) + tail))
        events.append(c.id, requested)
        events.append(c.id, result)
        val rows = first.dao().events(c.id)
        val fullJson = TaskEventJson.encode(result)
        assertEquals(TaskEventJson.encode(requested), rows.first().payload)
        assertTrue(rows.last().payload.encodeToByteArray().size < 4096)
        assertFalse(tail in rows.last().payload)
        assertTrue(fullJson.length < 256 * 1024)
        assertTrue(fullJson.encodeToByteArray().size > 256 * 1024)
        first.close()

        val second = open(file)
        try {
            val reopened = repository(second.dao())
            assertEquals(listOf(requested, result), reopened.history(c.id))
            assertEquals(listOf(requested, result), reopened.observe(c.id).first())
            val blob = blobRoot.walkTopDown().single { it.isFile }
            assertEquals(7, UUID.fromString(blob.name).version())
            RandomAccessFile(blob, "rw").use { it.writeByte('x'.code) }
            assertEquals(TaskEvent.Unknown(result.id, result.taskId, result.at), reopened.history(c.id).last())
            assertEquals(listOf("t"), reopened.unfinishedTasks().map { it.taskId })
            assertTrue(blob.delete())
            assertEquals(TaskEvent.Unknown(result.id, result.taskId, result.at), reopened.history(c.id).last())
            assertEquals(listOf("t"), reopened.unfinishedTasks().map { it.taskId })
        } finally {
            second.close()
        }
    }

    @Test
    fun recoveryClosesPersistedApprovalRequestsAndResolutionsWithoutReplayingEither() = runTest {
        val file = folder.newFile("lumynoid.db")
        val first = open(file)
        val conversation = RoomConversationPort(first.dao(), UuidV7, Clock { 1L }).create("approvals")
        val events = repository(first.dao())
        val waitingCall = TaskEvent.RequestedToolCall(
            ToolCall("wait-call", "web_fixture", """{"text":"${"界".repeat(90_000)}"}"""),
            UiText("card.fixture"),
        )
        val resolvedCall = waitingCall.copy(call = ToolCall("resolved-call", "web_fixture", "{}"))
        val waiting = TaskEvent.ApprovalRequested(
            "request-wait", "waiting", 2L, ToolApproval("wait-id", waitingCall, RiskAssessment.EGRESS, 43_200_002L),
        )
        val request = TaskEvent.ApprovalRequested(
            "request-resolved", "resolved", 3L, ToolApproval("resolved-id", resolvedCall, RiskAssessment.EGRESS, 43_200_003L),
        )
        val resolution = TaskEvent.ApprovalResolved(
            "resolution", "resolved", 4L, request.approval.id, resolvedCall.call.id, ApprovalResolution.APPROVED,
        )
        for (taskId in listOf(waiting.taskId, request.taskId)) {
            events.createTask(taskId, TaskRequest(Origin.User, conversation.id, "ws", "x"), 1L)
        }
        for (event in listOf(waiting, request, resolution)) events.append(conversation.id, event)
        val rows = first.dao().events(conversation.id)
        assertTrue(StoredEventBlob.isEnvelope(rows.first().payload))
        assertTrue(rows[1].payload.startsWith("""{"type":"approval_requested""""))
        assertTrue(rows.last().payload.startsWith("""{"type":"approval_resolved""""))
        first.close()

        val second = open(file)
        try {
            val reopened = repository(second.dao())
            assertEquals(listOf(waiting, request, resolution), reopened.observe(conversation.id).first())
            assertTrue(blobRoot.walkTopDown().single { it.isFile }.delete())
            val pending = reopened.unfinishedTasks()
            assertEquals(setOf(waiting.taskId, request.taskId), pending.map { it.taskId }.toSet())
            var modelCalls = 0
            var registrySnapshots = 0
            val runner = TaskRunner(
                LlmPort { modelCalls++; emptyFlow() }, reopened, backgroundScope,
                tools = ToolRegistry(listOf(ToolProvider { registrySnapshots++; emptyList() })),
            )
            runner.pendingConversations.first { it != null }

            val afterRecovery = reopened.history(conversation.id).drop(3).map { assertIs<TaskEvent.TaskFailed>(it) }
            assertEquals(pending.map { it.taskId }.toSet(), afterRecovery.map { it.taskId }.toSet())
            assertTrue(afterRecovery.all { it.reason == FailureReason.INTERRUPTED })
            assertTrue(reopened.unfinishedTasks().isEmpty())
            assertFalse(runner.resolve(waiting.approval.id, ApprovalDecision.APPROVE))
            assertEquals(0, modelCalls)
            assertEquals(0, registrySnapshots)
        } finally {
            second.close()
        }
    }

    @Test
    fun recoveryClosesTasksEndingInRejectionOrRetryWithoutReplaying() = runTest {
        val file = folder.newFile("lumynoid.db")
        val first = open(file)
        val conversation = RoomConversationPort(first.dao(), UuidV7, Clock { 1L }).create("retries")
        val events = repository(first.dao())
        val rejected = TaskEvent.ToolCallRejected("reject-only", "rejected", 2L, 1)
        val retryRejection = TaskEvent.ToolCallRejected("reject-before-retry", "retrying", 3L, 1)
        val retry = TaskEvent.LlmRetry("retry", retryRejection.taskId, 4L, 2)
        val audit = listOf(rejected, retryRejection, retry)
        val taskIds = setOf(rejected.taskId, retry.taskId)
        for (taskId in taskIds) {
            events.createTask(taskId, TaskRequest(Origin.User, conversation.id, "ws", "x"), 1L)
        }
        for (event in audit) events.append(conversation.id, event)
        val rows = first.dao().events(conversation.id)
        assertTrue(rows.first().payload.startsWith("""{"type":"tool_call_rejected""""))
        assertTrue(rows.last().payload.startsWith("""{"type":"llm_retry""""))
        first.close()

        val second = open(file)
        try {
            val reopened = repository(second.dao())
            assertEquals(audit, reopened.history(conversation.id))
            assertEquals(taskIds, reopened.unfinishedTasks().map { it.taskId }.toSet())
            var modelCalls = 0
            var registrySnapshots = 0
            val runner = TaskRunner(
                LlmPort { modelCalls++; emptyFlow() }, reopened, backgroundScope,
                tools = ToolRegistry(listOf(ToolProvider { registrySnapshots++; emptyList() })),
            )
            runner.pendingConversations.first { it != null }

            val interrupted = reopened.history(conversation.id).drop(audit.size).map { assertIs<TaskEvent.TaskFailed>(it) }
            assertEquals(taskIds, interrupted.map { it.taskId }.toSet())
            assertTrue(interrupted.all { it.reason == FailureReason.INTERRUPTED })
            assertTrue(reopened.unfinishedTasks().isEmpty())
            assertEquals(0, modelCalls)
            assertEquals(0, registrySnapshots)
        } finally {
            second.close()
        }
    }

    @Test
    fun providerConfigKeepsOnlyASecretReferenceNotTheKey() = runTest {
        val db = open(folder.newFile("lumynoid.db"))
        val store = RoomProviderConfigStore(db.dao())

        store.save(ProviderConfig("p1", "Mine", null, "https://example.test/v1", SecretRef("provider_key_p1")))
        val row = db.dao().provider("p1")

        assertNotNull(row)
        assertEquals("provider_key_p1", row.secretRef)
        assertFalse(row.toString().contains("sk-"))
        assertEquals(SecretRef("provider_key_p1"), store.provider("p1")?.apiKey)
        db.close()
    }

    @Test
    fun providersModelsAndTheActiveChoiceRoundTrip() = runTest {
        val db = open(folder.newFile("lumynoid.db"))
        val store = RoomProviderConfigStore(db.dao())
        assertEquals(null, store.active())

        store.save(ProviderConfig("p1", "One", "openrouter", "https://one.test/v1", SecretRef("k1")))
        store.save(ProviderConfig("p2", "Two", "anthropic", "https://two.test/v1", SecretRef("k2"), ProviderKind.ANTHROPIC))
        store.saveModel(ModelProfile("p1", "a"))
        store.saveModel(ModelProfile("p1", "b"))
        store.saveModel(ModelProfile("p1", "a")) // saved again: now the most recent
        store.setActive(ActiveModel("p1", "b"))
        store.setActive(ActiveModel("p2", "z"))

        assertEquals(listOf("p1", "p2"), store.providers().map { it.id })
        assertEquals("openrouter", store.provider("p1")?.presetId)
        assertEquals(ProviderKind.OPENAI_COMPATIBLE, store.provider("p1")?.kind)
        assertEquals(ProviderKind.ANTHROPIC, store.provider("p2")?.kind)
        assertEquals(listOf("b", "a"), store.models("p1").map { it.modelId })
        assertEquals(ActiveModel("p2", "z"), store.active())
        db.close()
    }

    @Test
    fun conversationsAreOrderedByLastActivityAndTitledFromTheFirstMessage() = runTest {
        val db = open(folder.newFile("lumynoid.db"))
        var now = 0L
        val port = RoomConversationPort(db.dao(), UuidV7, Clock { now })
        val events = repository(db.dao())
        now = 1; val a = port.create("  first\nline  ")
        now = 2; val b = port.create("second")
        assertEquals(listOf(b.id, a.id), port.observeAll().first().map { it.id })
        assertEquals("first", a.title)

        events.append(a.id, TaskEvent.TaskQueued("q", "t", 50L))
        val all = port.observeAll().first()
        assertEquals(listOf(a.id, b.id), all.map { it.id })
        assertEquals(50L, all.first().lastActivityAt)
        assertEquals(2L, all.last().lastActivityAt)
        assertEquals(a.id, port.latest()?.id)
        db.close()
    }

    @Test
    fun renameKeepsTheOldTitleWhenBlank() = runTest {
        val db = open(folder.newFile("lumynoid.db"))
        val port = RoomConversationPort(db.dao(), UuidV7, Clock { 1L })
        val c = port.create("old")

        assertFalse(port.rename(c.id, "   "))
        assertEquals("old", port.latest()?.title)
        assertEquals(true, port.rename(c.id, "  new name "))
        assertEquals("new name", port.latest()?.title)
        db.close()
    }

    @Test
    fun deleteRemovesTheConversationItsTasksAndEventsAndLeavesOthersIntact() = runTest {
        val db = open(folder.newFile("lumynoid.db"))
        val port = RoomConversationPort(db.dao(), UuidV7, Clock { 1L })
        val events = repository(db.dao())
        val gone = port.create("gone")
        val kept = port.create("kept")
        for ((c, t) in listOf(gone.id to "t1", kept.id to "t2")) {
            events.createTask(t, TaskRequest(Origin.User, c, "ws", "x"), 1L)
            events.append(c, TaskEvent.UserMessage("e-$t", t, 2L, Origin.User, "x"))
        }

        port.delete(gone.id)

        assertEquals(listOf(kept.id), port.observeAll().first().map { it.id })
        assertEquals(emptyList(), events.history(gone.id))
        assertEquals(listOf("e-t2"), events.history(kept.id).map { it.id })
        assertEquals(1, db.dao().taskCount())
        db.close()
    }

    @Test
    fun writesForADeletedConversationLeaveNoRows() = runTest {
        val db = open(folder.newFile("lumynoid.db"))
        val port = RoomConversationPort(db.dao(), UuidV7, Clock { 1L })
        val events = repository(db.dao())
        val c = port.create("gone")
        port.delete(c.id)

        events.createTask("t1", TaskRequest(Origin.User, c.id, "ws", "x"), 2L)
        events.append(c.id, TaskEvent.UserMessage("e1", "t1", 2L, Origin.User, "x"))
        val call = TaskEvent.RequestedToolCall(ToolCall("call", "web_fixture_lookup", "{}"), UiText("card.fixture.lookup"))
        assertFailsWith<IllegalStateException> {
            events.append(c.id, TaskEvent.ToolCallRequested("e2", "t1", 3L, "", listOf(call)))
        }
        assertFailsWith<IllegalStateException> {
            events.append(c.id, TaskEvent.ToolResult("e3", "t1", 4L, call.call.id, ToolOutcome("not stored")))
        }
        val approval = ToolApproval("approval", call, RiskAssessment.EGRESS, 5L)
        assertFailsWith<IllegalStateException> {
            events.append(c.id, TaskEvent.ApprovalRequested("e4", "t1", 4L, approval))
        }
        assertFailsWith<IllegalStateException> {
            events.append(c.id, TaskEvent.ApprovalResolved("e5", "t1", 4L, approval.id, call.call.id, ApprovalResolution.APPROVED))
        }
        assertFailsWith<IllegalStateException> {
            events.append(c.id, TaskEvent.ToolCallRejected("e6", "t1", 5L, 1))
        }
        assertFailsWith<IllegalStateException> {
            events.append(c.id, TaskEvent.LlmRetry("e7", "t1", 5L, 2))
        }

        assertEquals(0, db.dao().taskCount())
        assertEquals(emptyList(), events.history(c.id))
        db.close()
    }

    @Test
    fun unfinishedTasksListsEveryTaskWhoseLastEventIsNotTerminal() = runTest {
        val db = open(folder.newFile("lumynoid.db"))
        val port = RoomConversationPort(db.dao(), UuidV7, Clock { 1L })
        val events = repository(db.dao())
        val c = port.create("x")
        val other = port.create("y")
        suspend fun create(id: String, conv: String = c.id) = events.createTask(id, TaskRequest(Origin.User, conv, "ws", "x"), 1L)
        suspend fun add(event: TaskEvent, conv: String = c.id) = events.append(conv, event)

        create("none")
        create("queued"); add(TaskEvent.TaskQueued("q1", "queued", 1L))
        create("user"); add(TaskEvent.TaskQueued("q2", "user", 1L)); add(TaskEvent.UserMessage("u2", "user", 2L, Origin.User, "x"))
        create("answered"); add(TaskEvent.UserMessage("u3", "answered", 1L, Origin.User, "x")); add(TaskEvent.AssistantMessage("a3", "answered", 2L, "y"))
        val call = ToolCall("c", "web_fixture_lookup", "{}")
        create("requested")
        add(TaskEvent.ToolCallRequested(
            "requested", "requested", 2L, "", listOf(TaskEvent.RequestedToolCall(call, UiText("card.fixture.lookup"))),
        ))
        create("result")
        add(TaskEvent.ToolResult("result", "result", 2L, call.id, ToolOutcome("x")))
        create("finished"); add(TaskEvent.UserMessage("u4", "finished", 1L, Origin.User, "x")); add(TaskEvent.TaskFinished("f4", "finished", 2L))
        create("failed"); add(TaskEvent.TaskFailed("f5", "failed", 2L, FailureReason.INTERRUPTED))
        create("cancelled"); add(TaskEvent.UserMessage("u6", "cancelled", 1L, Origin.User, "x")); add(TaskEvent.TaskCancelled("c6", "cancelled", 2L))
        create("unknown", other.id); add(TaskEvent.Unknown("k7", "unknown", 2L), other.id)
        create("unreadable", other.id)
        db.dao().insertEvent(TaskEventEntity(id = "bad", taskId = "unreadable", conversationId = other.id, at = 3L, payload = "{not json"))
        create("other-open", other.id); add(TaskEvent.UserMessage("u8", "other-open", 1L, Origin.User, "x"), other.id)

        val unfinished = events.unfinishedTasks()

        assertEquals(
            setOf(
                "none" to c.id, "queued" to c.id, "user" to c.id, "answered" to c.id,
                "requested" to c.id, "result" to c.id, "other-open" to other.id,
            ),
            unfinished.map { it.taskId to it.conversationId }.toSet(),
        )
        db.close()
    }
}
