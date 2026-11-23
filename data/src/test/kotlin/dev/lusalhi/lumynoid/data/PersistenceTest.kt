package dev.lusalhi.lumynoid.data

import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import dev.lusalhi.lumynoid.core.ActiveModel
import dev.lusalhi.lumynoid.core.ApprovalDecision
import dev.lusalhi.lumynoid.core.ApprovalResolution
import dev.lusalhi.lumynoid.core.Clock
import dev.lusalhi.lumynoid.core.ConversationWorkspace
import dev.lusalhi.lumynoid.core.FailureReason
import dev.lusalhi.lumynoid.core.LlmChunk
import dev.lusalhi.lumynoid.core.LlmMessage
import dev.lusalhi.lumynoid.core.LlmPort
import dev.lusalhi.lumynoid.core.LlmRequest
import dev.lusalhi.lumynoid.core.ModelProfile
import dev.lusalhi.lumynoid.core.Origin
import dev.lusalhi.lumynoid.core.ProjectPort
import dev.lusalhi.lumynoid.core.ProviderConfig
import dev.lusalhi.lumynoid.core.ProviderKind
import dev.lusalhi.lumynoid.core.RiskAssessment
import dev.lusalhi.lumynoid.core.Role
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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import java.util.Base64
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Runs the real Room schema on a real SQLite file, closing and reopening it like a process restart. */
class PersistenceTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val blobRoot get() = File(folder.root, "event-blobs")

    private fun repository(dao: LumynoidDao): RoomEventRepository =
        RoomEventRepository(dao, EventBlobStore(blobRoot))

    private fun conversationPort(
        dao: LumynoidDao,
        clock: Clock = Clock { 1L },
        withLifecycle: suspend (suspend (Set<String>) -> Unit) -> Unit = { action -> action(emptySet()) },
        deleteWhenIdle: suspend (String, suspend () -> Unit) -> Boolean = { _, delete -> delete(); true },
    ): RoomConversationPort =
        RoomConversationPort(dao, UuidV7, clock, EventBlobStore(blobRoot), withLifecycle, deleteWhenIdle)

    private fun projectPort(
        dao: LumynoidDao,
        conversations: RoomConversationPort = conversationPort(dao),
        withLifecycle: suspend (suspend (Set<String>) -> Unit) -> Unit = { action -> action(emptySet()) },
        deleteWorkspace: suspend (String) -> Unit = {},
    ): RoomProjectPort = RoomProjectPort(dao, UuidV7, conversations, withLifecycle, deleteWorkspace)

    private fun open(file: File): LumynoidDatabase =
        Room.databaseBuilder<LumynoidDatabase>(file.path) { LumynoidDatabase_Impl() }
            .setDriver(BundledSQLiteDriver())
            .build()

    @Test
    fun conversationAndEventsSurviveClosingAndReopeningTheDatabase() = runTest {
        val file = folder.newFile("lumynoid.db")

        val first = open(file)
        val conversation = conversationPort(first.dao(), Clock { 10L }).create("hello")
        val events = repository(first.dao())
        events.createTask("t1", TaskRequest(Origin.User, conversation.id, "hello"), 10L, workspaceId = null)
        events.append(conversation.id, TaskEvent.UserMessage("e1", "t1", 10L, Origin.User, "hello"))
        events.append(conversation.id, TaskEvent.AssistantMessage("e2", "t1", 11L, "world"))
        events.append(conversation.id, TaskEvent.TaskFinished("e3", "t1", 12L))
        first.close()

        val second = open(file)
        val reopened = repository(second.dao()).observe(conversation.id).first()
        assertEquals(listOf("e1", "e2", "e3"), reopened.map { it.id })
        assertEquals("world", (reopened[1] as TaskEvent.AssistantMessage).text)
        assertEquals(conversation.id, conversationPort(second.dao(), Clock { 20L }).latest()?.id)
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
        val c = conversationPort(first.dao()).create("x")
        val events = repository(first.dao())
        events.createTask("t", TaskRequest(Origin.User, c.id, "x"), 1L, workspaceId = null)
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
        val conversation = conversationPort(first.dao()).create("approvals")
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
            events.createTask(taskId, TaskRequest(Origin.User, conversation.id, "x"), 1L, workspaceId = null)
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
                resolveWorkspace = conversationPort(second.dao())::workspace,
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
    fun recoveryClosesTasksEndingInRejectionRetryOrExecutionDenialWithoutReplaying() = runTest {
        val file = folder.newFile("lumynoid.db")
        val first = open(file)
        val conversation = conversationPort(first.dao()).create("retries")
        val events = repository(first.dao())
        val rejected = TaskEvent.ToolCallRejected("reject-only", "rejected", 2L, 1)
        val retryRejection = TaskEvent.ToolCallRejected("reject-before-retry", "retrying", 3L, 1)
        val retry = TaskEvent.LlmRetry("retry", retryRejection.taskId, 4L, 2)
        val denied = TaskEvent.ToolExecutionDenied("denied-only", "denied", 5L, "call-denied")
        val audit = listOf(rejected, retryRejection, retry, denied)
        val taskIds = setOf(rejected.taskId, retry.taskId, denied.taskId)
        for (taskId in taskIds) {
            events.createTask(taskId, TaskRequest(Origin.User, conversation.id, "x"), 1L, workspaceId = null)
        }
        for (event in audit) events.append(conversation.id, event)
        val rows = first.dao().events(conversation.id)
        assertTrue(rows.first().payload.startsWith("""{"type":"tool_call_rejected""""))
        assertTrue(rows[2].payload.startsWith("""{"type":"llm_retry""""))
        assertTrue(rows.last().payload.startsWith("""{"type":"tool_execution_denied""""))
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
                resolveWorkspace = conversationPort(second.dao())::workspace,
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
        val port = conversationPort(db.dao(), Clock { now })
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
        val port = conversationPort(db.dao())
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
        val port = conversationPort(db.dao())
        val events = repository(db.dao())
        val gone = port.create("gone")
        val kept = port.create("kept")
        for ((c, t) in listOf(gone.id to "t1", kept.id to "t2")) {
            events.createTask(t, TaskRequest(Origin.User, c, "x"), 1L, workspaceId = null)
            events.append(c, TaskEvent.UserMessage("e-$t", t, 2L, Origin.User, "x"))
        }

        assertTrue(port.delete(gone.id))

        assertEquals(listOf(kept.id), port.observeAll().first().map { it.id })
        assertEquals(emptyList(), events.history(gone.id))
        assertEquals(listOf("e-t2"), events.history(kept.id).map { it.id })
        assertEquals(1, db.dao().taskCount())

        assertTrue(port.delete(gone.id)) // already gone: cleanup stays idempotent
        assertEquals(listOf(kept.id), port.observeAll().first().map { it.id })
        db.close()
    }

    @Test
    fun deletingOneProjectChatKeepsSharedFilesAndTheOtherChatsLogAfterReopen() = runTest {
        val file = folder.newFile("lumynoid.db")
        val first = open(file)
        var gateOpen = true
        val port = conversationPort(
            first.dao(),
            deleteWhenIdle = { _, delete -> if (gateOpen) { delete(); true } else false },
        )
        val events = repository(first.dao())
        val projects = projectPort(first.dao(), port)
        val project = assertNotNull(projects.create("  Shared work  "))
        assertTrue(projects.saveInstructions(project.id, "Keep the output compact."))
        assertFalse(projects.saveInstructions(project.id, "x".repeat(2_001)))
        val a = port.create("a", project.id)
        val b = port.create("b", project.id)
        val sharedFile = File(folder.root, "workspaces/${project.workspaceId}/notes.txt").apply {
            Files.createDirectories(toPath().parent)
            writeText("project notes")
        }
        val packageFile = File(folder.root, "coding-installs/${project.workspaceId}/module.js").apply {
            Files.createDirectories(toPath().parent)
            writeText("packaged module")
        }
        val tail = "END OF FULL TOOL OUTPUT"
        fun result(taskId: String) = TaskEvent.ToolResult(
            "result-$taskId", taskId, 3L, "call-$taskId", ToolOutcome("界".repeat(90_000) + tail),
        )
        for ((c, t) in listOf(a.id to "ta", b.id to "tb")) {
            events.createTask(t, TaskRequest(Origin.User, c, "x"), 1L, project.workspaceId)
            events.append(c, TaskEvent.ToolCallRequested(
                "request-$t", t, 2L, "",
                listOf(TaskEvent.RequestedToolCall(ToolCall("call-$t", "web_fixture_lookup", "{}"), UiText("card.fixture.lookup"))),
            ))
            events.append(c, result(t))
            events.append(c, TaskEvent.TaskFinished("finished-$t", t, 4L))
        }
        fun blobDirectory(conversationId: String) =
            File(blobRoot, Base64.getUrlEncoder().withoutPadding().encodeToString(conversationId.encodeToByteArray()))
        val blobsA = blobDirectory(a.id)
        val blobsB = blobDirectory(b.id)
        assertEquals(1, blobsA.walkTopDown().filter { it.isFile }.count())
        assertEquals(1, blobsB.walkTopDown().filter { it.isFile }.count())
        // A link inside A's tree must never pull B's files into the deletion.
        Files.createSymbolicLink(File(blobsA, "escape").toPath(), blobsB.toPath())

        // Unfinished work blocks deletion: rows, blobs and workspace stay intact.
        gateOpen = false
        assertFalse(port.delete(a.id))
        assertTrue(blobsA.exists() && blobsB.exists())
        assertEquals(2, first.dao().taskCount())
        assertEquals(3, events.history(a.id).size)

        gateOpen = true
        assertTrue(port.delete(a.id))
        assertFalse(blobsA.exists())
        assertEquals(1, blobsB.walkTopDown().filter { it.isFile }.count())
        assertEquals(listOf(b.id), port.observeAll().first().map { it.id })
        assertEquals(emptyList(), events.history(a.id))
        first.close()

        val second = open(file)
        try {
            val reopened = repository(second.dao())
            assertEquals(emptyList(), reopened.history(a.id))
            val keptResult = reopened.history(b.id).filterIsInstance<TaskEvent.ToolResult>().single()
            assertEquals(result("tb"), keptResult)
            assertTrue(keptResult.outcome.output.endsWith(tail))
            assertFalse(blobsA.exists())
            assertEquals(1, blobsB.walkTopDown().filter { it.isFile }.count())
            val conversations = conversationPort(second.dao())
            assertEquals(b.copy(lastActivityAt = 4L), conversations.latest())
            assertEquals(ConversationWorkspace.Project(project.workspaceId), conversations.workspace(b.id))
            assertEquals(null, conversations.workspace(a.id))
            assertEquals(
                project.copy(name = "Shared work", instructions = "Keep the output compact."),
                projectPort(second.dao()).observeAll().first().single(),
            )
            assertEquals("project notes", sharedFile.readText())
            assertEquals("packaged module", packageFile.readText())
        } finally {
            second.close()
        }
    }

    @Test
    fun writesForADeletedConversationLeaveNoRowsOrBlobs() = runTest {
        val db = open(folder.newFile("lumynoid.db"))
        val port = conversationPort(db.dao())
        val events = repository(db.dao())
        val c = port.create("gone")
        assertTrue(port.delete(c.id))

        assertFailsWith<IllegalStateException> {
            events.createTask("t1", TaskRequest(Origin.User, c.id, "x"), 2L, workspaceId = null)
        }
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
            events.append(c.id, TaskEvent.ToolExecutionDenied("e7", "t1", 5L, call.call.id))
        }
        assertFailsWith<IllegalStateException> {
            events.append(c.id, TaskEvent.LlmRetry("e8", "t1", 5L, 2))
        }
        // Stale large payloads publish a blob before the insert fails; none may survive as an orphan.
        assertFailsWith<IllegalStateException> {
            events.append(c.id, TaskEvent.ToolResult("e9", "t1", 6L, call.call.id, ToolOutcome("界".repeat(90_000))))
        }
        events.append(c.id, TaskEvent.UserMessage("e10", "t1", 7L, Origin.User, "界".repeat(90_000)))

        assertEquals(0, db.dao().taskCount())
        assertEquals(emptyList(), events.history(c.id))
        assertEquals(emptyList(), blobRoot.walkTopDown().filter { it.isFile }.toList())
        db.close()
    }

    @Test
    fun unfinishedTasksListsEveryTaskWhoseLastEventIsNotTerminal() = runTest {
        val db = open(folder.newFile("lumynoid.db"))
        val port = conversationPort(db.dao())
        val events = repository(db.dao())
        val c = port.create("x")
        val other = port.create("y")
        suspend fun create(id: String, conv: String = c.id) =
            events.createTask(id, TaskRequest(Origin.User, conv, "x"), 1L, workspaceId = null)
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

    @Test
    fun deleteWaitsForTheRunnersGateUntilTheConversationIsIdle() = runTest {
        val db = open(folder.newFile("lumynoid.db"))
        val events = repository(db.dao())
        val release = CompletableDeferred<Unit>()
        val runner = TaskRunner(
            LlmPort { flow { emit(LlmChunk.TextDelta("working")); release.await(); emit(LlmChunk.Done) } },
            events, backgroundScope,
            resolveWorkspace = conversationPort(db.dao())::workspace,
        )
        runner.pendingConversations.first { it != null }
        val port = conversationPort(
            db.dao(),
            withLifecycle = runner::withConversationLifecycle,
            deleteWhenIdle = runner::deleteWhenIdle,
        )
        val c = port.create("busy")

        runner.submit(TaskRequest(Origin.User, c.id, "x"))
        runner.active.first { it?.conversationId == c.id }
        assertFalse(port.delete(c.id))
        assertEquals(listOf(c.id), port.observeAll().first().map { it.id })
        assertTrue(events.history(c.id).isNotEmpty())

        release.complete(Unit)
        runner.pendingConversations.first { it!!.isEmpty() }
        assertTrue(port.delete(c.id))
        assertEquals(emptyList(), events.history(c.id))
        assertEquals(0, db.dao().taskCount())
        db.close()
    }

    @Test
    fun deletionRefusesASymlinkedBlobRootButUnlinksASymlinkedLeaf() = runTest {
        val db = open(folder.newFile("lumynoid.db"))
        val port = conversationPort(db.dao())
        val events = repository(db.dao())
        val c = port.create("guarded")
        events.createTask("t", TaskRequest(Origin.User, c.id, "x"), 1L, workspaceId = null)
        events.append(c.id, TaskEvent.TaskFinished("f", "t", 2L))
        val victim = folder.newFolder("victim")
        val kept = File(victim, "kept.txt").apply { writeText("kept") }
        Files.createSymbolicLink(blobRoot.toPath(), victim.toPath())

        assertFailsWith<IllegalStateException> { port.delete(c.id) }
        assertEquals(listOf(c.id), port.observeAll().first().map { it.id })
        assertEquals(1, events.history(c.id).size)
        val store = EventBlobStore(blobRoot)
        assertFailsWith<IllegalStateException> { store.deleteConversation(c.id) }
        assertFailsWith<IllegalStateException> { store.discard(c.id, EventBlobRef(UUID.randomUUID().toString(), 0, "")) }
        assertEquals("kept", kept.readText())

        // With a real root, a symlinked conversation leaf is unlinked without touching its target.
        assertTrue(blobRoot.delete())
        Files.createDirectories(blobRoot.toPath())
        val leaf = File(blobRoot, Base64.getUrlEncoder().withoutPadding().encodeToString(c.id.encodeToByteArray()))
        Files.createSymbolicLink(leaf.toPath(), victim.toPath())
        assertTrue(port.delete(c.id))
        assertFalse(leaf.exists())
        assertEquals("kept", kept.readText())
        assertEquals(emptyList(), events.history(c.id))
        db.close()
    }

    @Test
    fun instructionsRoundTripByWorkspaceWithUnicodeLimitsAndClearing() = runTest {
        val file = folder.newFile("lumynoid.db")
        val first = open(file)
        val projects = projectPort(first.dao())
        val project = assertNotNull(projects.create("Rules"))
        val other = assertNotNull(projects.create("Other"))
        assertEquals("", projects.instructionsForWorkspace(project.workspaceId))
        assertNull(projects.instructionsForWorkspace("no-such-workspace"))

        // Multiline text is stored verbatim, without trimming, and keyed by the unique workspace.
        val multiline = "  Keep answers short.\nUse metric units.\n\tindented  "
        assertTrue(projects.saveInstructions(project.id, multiline))
        assertEquals(multiline, projects.instructionsForWorkspace(project.workspaceId))
        assertTrue(projects.saveInstructions(other.id, "Other rules"))
        assertEquals("Other rules", projects.instructionsForWorkspace(other.workspaceId))

        // Exactly 2,000 code points, including supplementary ones, persist in full.
        val atLimit = "line\n" + "😀".repeat(ProjectPort.MAX_INSTRUCTION_CODE_POINTS - 5)
        assertEquals(ProjectPort.MAX_INSTRUCTION_CODE_POINTS, atLimit.codePointCount(0, atLimit.length))
        assertTrue(projects.saveInstructions(project.id, atLimit))
        assertEquals(atLimit, projects.instructionsForWorkspace(project.workspaceId))

        // One code point over is rejected without touching the stored value.
        assertFalse(projects.saveInstructions(project.id, atLimit + "𝄞"))
        assertEquals(atLimit, projects.instructionsForWorkspace(project.workspaceId))
        first.close()

        val second = open(file)
        try {
            val reopened = projectPort(second.dao())
            assertEquals(atLimit, reopened.instructionsForWorkspace(project.workspaceId))
            assertEquals(atLimit, reopened.observeAll().first().single { it.id == project.id }.instructions)
            assertEquals("Other rules", reopened.instructionsForWorkspace(other.workspaceId))
            assertTrue(reopened.saveInstructions(project.id, ""))
            assertEquals("", reopened.instructionsForWorkspace(project.workspaceId))
        } finally {
            second.close()
        }
    }

    @Test
    fun theRunnerSendsStoredProjectInstructionsAndRefreshesThemOnlyForNewTasks() = runTest {
        val db = open(folder.newFile("lumynoid.db"))
        val projects = projectPort(db.dao())
        val conversations = conversationPort(db.dao())
        val events = repository(db.dao())
        val project = assertNotNull(projects.create("Rules"))
        assertTrue(projects.saveInstructions(project.id, "Keep answers short.\nUse metric units."))
        val requests = mutableListOf<LlmRequest>()
        val runner = TaskRunner(
            LlmPort { sent ->
                requests += sent
                flow { emit(LlmChunk.TextDelta("ok")); emit(LlmChunk.Done) }
            },
            events, backgroundScope,
            resolveWorkspace = conversations::workspace,
            instructionsForWorkspace = projects::instructionsForWorkspace,
        )
        runner.pendingConversations.first { it != null }
        val projectChat = conversations.create("first", project.id)
        val plainChat = conversations.create("ordinary")

        suspend fun turn(conversationId: String, input: String) {
            runner.submit(TaskRequest(Origin.User, conversationId, input))
            runner.pendingConversations.first { it!!.isEmpty() }
        }
        turn(projectChat.id, "hello")
        turn(plainChat.id, "hi")
        assertTrue(projects.saveInstructions(project.id, "Updated rules"))
        turn(projectChat.id, "again")

        assertEquals(
            listOf(LlmMessage(Role.SYSTEM, "Keep answers short.\nUse metric units."), LlmMessage(Role.USER, "hello")),
            requests[0].messages,
        )
        assertEquals(listOf(LlmMessage(Role.USER, "hi")), requests[1].messages)
        assertEquals(
            listOf(
                LlmMessage(Role.SYSTEM, "Updated rules"),
                LlmMessage(Role.USER, "hello"), LlmMessage(Role.ASSISTANT, "ok"),
                LlmMessage(Role.USER, "again"),
            ),
            requests[2].messages,
        )
        db.close()
    }
}
