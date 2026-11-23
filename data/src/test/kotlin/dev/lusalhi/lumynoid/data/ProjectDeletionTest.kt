package dev.lusalhi.lumynoid.data

import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import dev.lusalhi.lumynoid.core.ActiveModel
import dev.lusalhi.lumynoid.core.ApprovalDecision
import dev.lusalhi.lumynoid.core.Clock
import dev.lusalhi.lumynoid.core.ConversationWorkspace
import dev.lusalhi.lumynoid.core.LlmChunk
import dev.lusalhi.lumynoid.core.LlmPort
import dev.lusalhi.lumynoid.core.Origin
import dev.lusalhi.lumynoid.core.ProviderConfig
import dev.lusalhi.lumynoid.core.RiskAssessment
import dev.lusalhi.lumynoid.core.Role
import dev.lusalhi.lumynoid.core.SecretRef
import dev.lusalhi.lumynoid.core.TaskEvent
import dev.lusalhi.lumynoid.core.TaskRequest
import dev.lusalhi.lumynoid.core.TaskRunner
import dev.lusalhi.lumynoid.core.Tool
import dev.lusalhi.lumynoid.core.ToolCall
import dev.lusalhi.lumynoid.core.ToolContext
import dev.lusalhi.lumynoid.core.ToolOutcome
import dev.lusalhi.lumynoid.core.ToolProvider
import dev.lusalhi.lumynoid.core.ToolRegistry
import dev.lusalhi.lumynoid.core.UiText
import dev.lusalhi.lumynoid.core.UuidV7
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.Base64
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Story 8.6: whole-project deletion through the production Room graph. The userland boundary is
 * injected, so workspace ownership is proved deterministically; the runner supplies the real
 * admission gate wherever contention matters.
 */
class ProjectDeletionTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val blobRoot get() = File(folder.root, "event-blobs")

    private fun open(file: File): LumynoidDatabase =
        Room.databaseBuilder<LumynoidDatabase>(file.path) { LumynoidDatabase_Impl() }
            .setDriver(BundledSQLiteDriver())
            .build()

    private fun repository(dao: LumynoidDao): RoomEventRepository =
        RoomEventRepository(dao, EventBlobStore(blobRoot))

    private fun conversationPort(
        dao: LumynoidDao,
        withLifecycle: suspend (suspend (Set<String>) -> Unit) -> Unit = { action -> action(emptySet()) },
        deleteWhenIdle: suspend (String, suspend () -> Unit) -> Boolean = { _, delete -> delete(); true },
    ): RoomConversationPort =
        RoomConversationPort(dao, UuidV7, Clock { 1L }, EventBlobStore(blobRoot), withLifecycle, deleteWhenIdle)

    private fun projectPort(
        dao: LumynoidDao,
        chats: RoomConversationPort,
        withLifecycle: suspend (suspend (Set<String>) -> Unit) -> Unit = { action -> action(emptySet()) },
        deleteWorkspace: suspend (String) -> Unit = {},
    ): RoomProjectPort = RoomProjectPort(dao, UuidV7, chats, withLifecycle, deleteWorkspace)

    private fun blobDirectory(conversationId: String) =
        File(blobRoot, Base64.getUrlEncoder().withoutPadding().encodeToString(conversationId.encodeToByteArray()))

    private fun workspaceTree(workspaceId: String) =
        listOf(File(folder.root, "workspaces/$workspaceId"), File(folder.root, "coding-installs/$workspaceId"))

    private fun seedWorkspace(workspaceId: String) {
        File(folder.root, "workspaces/$workspaceId/notes.txt").apply { parentFile.mkdirs(); writeText("notes of $workspaceId") }
        File(folder.root, "coding-installs/$workspaceId/module.js").apply { parentFile.mkdirs(); writeText("package of $workspaceId") }
    }

    /** Deterministic stand-in for userland: removes only the workspace and its installed packages. */
    private fun removeWorkspace(workspaceId: String) {
        workspaceTree(workspaceId).forEach { it.deleteRecursively() }
    }

    private fun bigResult(taskId: String) = TaskEvent.ToolResult(
        "result-$taskId", taskId, 3L, "call-$taskId", ToolOutcome("界".repeat(90_000) + "END OF FULL TOOL OUTPUT"),
    )

    private suspend fun seedChat(events: RoomEventRepository, conversationId: String, taskId: String, workspaceId: String?) {
        events.createTask(taskId, TaskRequest(Origin.User, conversationId, "x"), 1L, workspaceId)
        events.append(conversationId, TaskEvent.ToolCallRequested(
            "request-$taskId", taskId, 2L, "",
            listOf(TaskEvent.RequestedToolCall(ToolCall("call-$taskId", "web_fixture_lookup", "{}"), UiText("card.fixture.lookup"))),
        ))
        events.append(conversationId, bigResult(taskId))
        events.append(conversationId, TaskEvent.TaskFinished("finished-$taskId", taskId, 4L))
    }

    @Test
    fun deleteRemovesOnlyTheProjectsChatsBlobsWorkspaceAndMetadata() = runTest {
        val file = folder.newFile("lumynoid.db")
        val first = open(file)
        val chats = conversationPort(first.dao())
        val events = repository(first.dao())
        val deleted = mutableListOf<String>()
        val projects = projectPort(first.dao(), chats, deleteWorkspace = { id -> deleted += id; removeWorkspace(id) })
        val providers = RoomProviderConfigStore(first.dao())
        providers.save(ProviderConfig("p1", "Mine", null, "https://example.test/v1", SecretRef("provider_key_p1")))
        providers.setActive(ActiveModel("p1", "m"))

        val doomed = assertNotNull(projects.create("Doomed"))
        val survivor = assertNotNull(projects.create("Survivor"))
        assertTrue(projects.saveInstructions(doomed.id, "Doomed rules"))
        assertTrue(projects.saveInstructions(survivor.id, "Survivor rules"))
        val a1 = chats.create("a1", doomed.id)
        val a2 = chats.create("a2", doomed.id)
        val b1 = chats.create("b1", survivor.id)
        val plain = chats.create("plain")
        seedWorkspace(doomed.workspaceId)
        seedWorkspace(survivor.workspaceId)
        val runtime = File(folder.root, "runtime/shared.txt").apply { parentFile.mkdirs(); writeText("shared runtime") }
        val legacy = File(folder.root, "workspaces/legacy-untouched/notes.txt").apply { parentFile.mkdirs(); writeText("legacy") }
        seedChat(events, a1.id, "ta1", doomed.workspaceId)
        seedChat(events, a2.id, "ta2", doomed.workspaceId)
        seedChat(events, b1.id, "tb1", survivor.workspaceId)
        // A link inside a doomed tree must never pull the survivor's blobs into the deletion.
        Files.createSymbolicLink(File(blobDirectory(a1.id), "escape").toPath(), blobDirectory(b1.id).toPath())

        assertTrue(projects.delete(doomed.id))

        assertEquals(listOf(doomed.workspaceId), deleted)
        assertEquals(listOf(b1.id, plain.id), chats.observeAll().first().map { it.id })
        assertEquals(b1.id, chats.latest()?.id)
        assertEquals(emptyList(), events.history(a1.id))
        assertEquals(emptyList(), events.history(a2.id))
        assertFalse(blobDirectory(a1.id).exists())
        assertFalse(blobDirectory(a2.id).exists())
        assertEquals(1, first.dao().taskCount())
        assertEquals(listOf(survivor.copy(instructions = "Survivor rules")), projects.observeAll().first())
        assertEquals(ConversationWorkspace.Project(survivor.workspaceId), chats.workspace(b1.id))
        assertEquals(ConversationWorkspace.Plain, chats.workspace(plain.id))
        assertEquals("Survivor rules", projects.instructionsForWorkspace(survivor.workspaceId))
        assertNull(projects.instructionsForWorkspace(doomed.workspaceId))
        workspaceTree(doomed.workspaceId).forEach { assertFalse(it.exists()) }
        workspaceTree(survivor.workspaceId).forEach { assertTrue(it.exists()) }
        assertEquals("shared runtime", runtime.readText())
        assertEquals("legacy", legacy.readText())
        assertEquals("provider_key_p1", providers.provider("p1")?.apiKey?.value)
        assertEquals(ActiveModel("p1", "m"), providers.active())
        first.close()

        val second = open(file)
        try {
            val reopenedEvents = repository(second.dao())
            assertEquals(bigResult("tb1"), reopenedEvents.history(b1.id).filterIsInstance<TaskEvent.ToolResult>().single())
            assertEquals(1, blobDirectory(b1.id).walkTopDown().filter { it.isFile }.count())
            assertEquals(emptyList(), reopenedEvents.history(a1.id))
            assertFalse(blobDirectory(a1.id).exists())
            val reopenedProjects = projectPort(
                second.dao(), conversationPort(second.dao()),
                deleteWorkspace = { id -> deleted += id; removeWorkspace(id) },
            )
            assertTrue(reopenedProjects.delete(doomed.id)) // already missing: no second boundary call
            assertEquals(listOf(doomed.workspaceId), deleted)
            assertEquals(listOf(survivor.copy(instructions = "Survivor rules")), reopenedProjects.observeAll().first())
        } finally {
            second.close()
        }
    }

    @Test
    fun deleteWaitsUntilNoMemberHasUnfinishedWorkAndOtherProjectsDoNotCount() = runTest {
        val db = open(folder.newFile("lumynoid.db"))
        val events = repository(db.dao())
        val scopePort = conversationPort(db.dao())
        var gate: CompletableDeferred<Unit>? = CompletableDeferred()
        val runner = TaskRunner(
            LlmPort { flow { emit(LlmChunk.TextDelta("working")); gate?.await(); emit(LlmChunk.Done) } },
            events, backgroundScope,
            resolveWorkspace = scopePort::workspace,
        )
        runner.pendingConversations.first { it != null }
        val chats = conversationPort(db.dao(), runner::withConversationLifecycle, runner::deleteWhenIdle)
        val deleted = mutableListOf<String>()
        val projects = projectPort(db.dao(), chats, runner::withConversationLifecycle, { id -> deleted += id; removeWorkspace(id) })
        val doomed = assertNotNull(projects.create("Doomed"))
        val other = assertNotNull(projects.create("Other"))
        val a = chats.create("a", doomed.id)
        val b = chats.create("b", other.id)
        seedWorkspace(doomed.workspaceId)

        // Its own running task blocks deletion without marking the project.
        runner.submit(TaskRequest(Origin.User, a.id, "x"))
        runner.active.first { it?.conversationId == a.id }
        assertFalse(projects.delete(doomed.id))
        assertEquals(listOf(doomed, other), projects.observeAll().first())
        assertEquals(ConversationWorkspace.Project(doomed.workspaceId), chats.workspace(a.id))
        assertTrue(events.history(a.id).isNotEmpty())
        assertEquals(emptyList(), deleted)

        gate?.complete(Unit)
        runner.pendingConversations.first { it!!.isEmpty() }

        // Another project's running task does not make this project busy.
        gate = CompletableDeferred()
        runner.submit(TaskRequest(Origin.User, b.id, "x"))
        runner.active.first { it?.conversationId == b.id }
        assertTrue(projects.delete(doomed.id))
        assertEquals(listOf(doomed.workspaceId), deleted)
        assertEquals(emptyList(), events.history(a.id))
        assertEquals(listOf(other), projects.observeAll().first())

        gate?.complete(Unit)
        runner.pendingConversations.first { it!!.isEmpty() }
        assertEquals(listOf(b.id), chats.observeAll().first().map { it.id })
        db.close()
    }

    @Test
    fun anApprovalWaitKeepsItsProjectUndeletableUntilTheDecisionLands() = runTest {
        val db = open(folder.newFile("lumynoid.db"))
        val events = repository(db.dao())
        val tool = object : Tool {
            override val name = "web_fixture_egress"
            override val description = "Fixture egress"
            override val inputSchema = JsonObject(emptyMap())
            override fun assess(args: JsonObject) = RiskAssessment.EGRESS
            override fun describe(args: JsonObject) = UiText("card.fixture.egress")
            override suspend fun execute(args: JsonObject, context: ToolContext) = ToolOutcome("approved result")
        }
        val scopePort = conversationPort(db.dao())
        val runner = TaskRunner(
            LlmPort { sent ->
                if (sent.messages.last().role == Role.TOOL) flow { emit(LlmChunk.TextDelta("done")); emit(LlmChunk.Done) }
                else flow { emit(LlmChunk.ToolCallDelta(0, tool.name, "{}")); emit(LlmChunk.Done) }
            },
            events, backgroundScope,
            tools = ToolRegistry(listOf(ToolProvider { listOf(tool) })),
            resolveWorkspace = scopePort::workspace,
        )
        runner.pendingConversations.first { it != null }
        val chats = conversationPort(db.dao(), runner::withConversationLifecycle, runner::deleteWhenIdle)
        val deleted = mutableListOf<String>()
        val projects = projectPort(db.dao(), chats, runner::withConversationLifecycle, { id -> deleted += id; removeWorkspace(id) })
        val project = assertNotNull(projects.create("Waiting"))
        val c = chats.create("c", project.id)
        seedWorkspace(project.workspaceId)

        runner.submit(TaskRequest(Origin.User, c.id, "x"))
        runner.pendingApprovals.first { it.isNotEmpty() }
        assertFalse(projects.delete(project.id))
        assertFalse(chats.delete(c.id))
        assertEquals(listOf(project), projects.observeAll().first())
        assertTrue(events.history(c.id).any { it is TaskEvent.ApprovalRequested })
        assertEquals(emptyList(), deleted)

        val approval = runner.pendingApprovals.value.values.single().event.approval
        assertTrue(runner.resolve(approval.id, ApprovalDecision.APPROVE))
        runner.pendingConversations.first { it!!.isEmpty() }

        assertTrue(projects.delete(project.id))
        assertEquals(listOf(project.workspaceId), deleted)
        assertEquals(emptyList(), events.history(c.id))
        assertEquals(emptyList(), projects.observeAll().first())
        db.close()
    }

    @Test
    fun aMarkedProjectRejectsNewWorkMembersAndInstructionsUntilResumeCompletes() = runTest {
        val file = folder.newFile("lumynoid.db")
        val first = open(file)
        val chats = conversationPort(first.dao())
        val events = repository(first.dao())
        val projects = projectPort(first.dao(), chats)
        val providers = RoomProviderConfigStore(first.dao())
        providers.save(ProviderConfig("p1", "Mine", null, "https://example.test/v1", SecretRef("provider_key_p1")))
        val doomed = assertNotNull(projects.create("Doomed"))
        val survivor = assertNotNull(projects.create("Survivor"))
        assertTrue(projects.saveInstructions(doomed.id, "Doomed rules"))
        assertTrue(projects.saveInstructions(survivor.id, "Survivor rules"))
        val a1 = chats.create("a1", doomed.id)
        val a2 = chats.create("a2", doomed.id)
        val b1 = chats.create("b1", survivor.id)
        seedChat(events, a1.id, "ta1", doomed.workspaceId)
        seedChat(events, a2.id, "ta2", doomed.workspaceId)
        seedChat(events, b1.id, "tb1", survivor.workspaceId)
        seedWorkspace(doomed.workspaceId)
        seedWorkspace(survivor.workspaceId)
        // The process died right after the deletion intent committed: marker set, owned data intact.
        first.dao().markProjectDeleting(doomed.id)
        first.close()

        val second = open(file)
        try {
            val reopenedChats = conversationPort(second.dao())
            val reopenedEvents = repository(second.dao())
            val deleted = mutableListOf<String>()
            val reopenedProjects = projectPort(second.dao(), reopenedChats, deleteWorkspace = { id -> deleted += id; removeWorkspace(id) })
            val runner = TaskRunner(
                LlmPort { flow { emit(LlmChunk.TextDelta("ok")); emit(LlmChunk.Done) } },
                reopenedEvents, backgroundScope,
                resolveWorkspace = reopenedChats::workspace,
            )
            runner.pendingConversations.first { it != null }

            assertEquals(
                listOf(doomed.copy(deleting = true, instructions = "Doomed rules"), survivor.copy(instructions = "Survivor rules")),
                reopenedProjects.observeAll().first(),
            )
            assertEquals(listOf(b1.id), reopenedChats.observeAll().first().map { it.id })
            assertEquals(b1.id, reopenedChats.latest()?.id)
            assertNull(reopenedChats.workspace(a1.id))
            assertNull(reopenedChats.workspace(a2.id))
            assertFailsWith<IllegalStateException> { runner.submit(TaskRequest(Origin.User, a1.id, "not admitted")) }
            assertFailsWith<IllegalStateException> { reopenedChats.create("late member", doomed.id) }
            assertFalse(reopenedProjects.saveInstructions(doomed.id, "new rules"))
            assertNull(reopenedProjects.instructionsForWorkspace(doomed.workspaceId))
            assertFalse(reopenedChats.delete(a1.id)) // the project deletion owns this chat now
            assertEquals(3, second.dao().taskCount())
            assertEquals(3, reopenedEvents.history(a1.id).size)
            assertTrue(blobDirectory(a1.id).exists())
            assertEquals(emptyList(), deleted)

            // The surviving project keeps working after the restart.
            runner.submit(TaskRequest(Origin.User, b1.id, "still working"))
            runner.pendingConversations.first { it!!.isEmpty() }
            assertTrue(reopenedEvents.history(b1.id).any { it is TaskEvent.TaskFinished && it.taskId != "tb1" })
            assertEquals("Survivor rules", reopenedProjects.instructionsForWorkspace(survivor.workspaceId))

            // Resume from persisted identity: only the doomed project's owned data goes.
            assertTrue(reopenedProjects.delete(doomed.id))
            assertEquals(listOf(doomed.workspaceId), deleted)
            assertEquals(emptyList(), reopenedEvents.history(a1.id))
            assertEquals(emptyList(), reopenedEvents.history(a2.id))
            assertFalse(blobDirectory(a1.id).exists())
            assertFalse(blobDirectory(a2.id).exists())
            assertEquals(bigResult("tb1"), reopenedEvents.history(b1.id).filterIsInstance<TaskEvent.ToolResult>().single { it.taskId == "tb1" })
            workspaceTree(doomed.workspaceId).forEach { assertFalse(it.exists()) }
            workspaceTree(survivor.workspaceId).forEach { assertTrue(it.exists()) }
            assertEquals(listOf(survivor.copy(instructions = "Survivor rules")), reopenedProjects.observeAll().first())
            assertEquals("provider_key_p1", RoomProviderConfigStore(second.dao()).provider("p1")?.apiKey?.value)
        } finally {
            second.close()
        }
    }

    @Test
    fun concurrentCleanupIsRejectedAndOtherProjectsWorkWhileTheBoundarySuspends() = runTest {
        val db = open(folder.newFile("lumynoid.db"))
        val events = repository(db.dao())
        val scopePort = conversationPort(db.dao())
        val runner = TaskRunner(
            LlmPort { flow { emit(LlmChunk.TextDelta("ok")); emit(LlmChunk.Done) } },
            events, backgroundScope,
            resolveWorkspace = scopePort::workspace,
        )
        runner.pendingConversations.first { it != null }
        val chats = conversationPort(db.dao(), runner::withConversationLifecycle, runner::deleteWhenIdle)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val deleted = mutableListOf<String>()
        val projects = projectPort(db.dao(), chats, runner::withConversationLifecycle, { id ->
            deleted += id
            entered.complete(Unit)
            release.await()
            removeWorkspace(id)
        })
        val doomed = assertNotNull(projects.create("Doomed"))
        val survivor = assertNotNull(projects.create("Survivor"))
        val a = chats.create("a", doomed.id)
        val b = chats.create("b", survivor.id)
        seedChat(events, a.id, "ta", doomed.workspaceId)
        seedWorkspace(doomed.workspaceId)
        seedWorkspace(survivor.workspaceId)

        val deletion = async { projects.delete(doomed.id) }
        entered.await()

        // The marker is persisted and the project is closed for new work while cleanup runs.
        assertEquals(listOf(doomed.copy(deleting = true), survivor), projects.observeAll().first())
        assertEquals(listOf(b.id), chats.observeAll().first().map { it.id })
        assertNull(chats.workspace(a.id))
        assertFailsWith<IllegalStateException> { runner.submit(TaskRequest(Origin.User, a.id, "not admitted")) }
        assertFailsWith<IllegalStateException> { chats.create("late member", doomed.id) }
        assertFalse(projects.saveInstructions(doomed.id, "new rules"))
        assertNull(projects.instructionsForWorkspace(doomed.workspaceId))
        assertFalse(projects.delete(doomed.id)) // cleanup already running

        // Another project keeps working while the boundary suspends.
        runner.submit(TaskRequest(Origin.User, b.id, "still working"))
        runner.pendingConversations.first { it!!.isEmpty() }
        assertTrue(events.history(b.id).any { it is TaskEvent.TaskFinished })

        release.complete(Unit)
        assertTrue(deletion.await())
        assertEquals(listOf(doomed.workspaceId), deleted)
        assertEquals(emptyList(), events.history(a.id))
        assertFalse(blobDirectory(a.id).exists())
        workspaceTree(doomed.workspaceId).forEach { assertFalse(it.exists()) }
        workspaceTree(survivor.workspaceId).forEach { assertTrue(it.exists()) }
        assertEquals(listOf(survivor), projects.observeAll().first())
        assertTrue(projects.delete(doomed.id)) // already gone
        assertEquals(listOf(doomed.workspaceId), deleted)
        db.close()
    }

    @Test
    fun aFailedCleanupKeepsTheMarkerAndResumeRetriesTheBoundary() = runTest {
        val file = folder.newFile("lumynoid.db")
        val first = open(file)
        val chats = conversationPort(first.dao())
        val events = repository(first.dao())
        val attempts = mutableListOf<String>()
        val projects = projectPort(first.dao(), chats, deleteWorkspace = { id -> attempts += id; throw IOException("workspace volume gone") })
        val doomed = assertNotNull(projects.create("Doomed"))
        val survivor = assertNotNull(projects.create("Survivor"))
        val a1 = chats.create("a1", doomed.id)
        val a2 = chats.create("a2", doomed.id)
        val b1 = chats.create("b1", survivor.id)
        seedChat(events, a1.id, "ta1", doomed.workspaceId)
        seedChat(events, a2.id, "ta2", doomed.workspaceId)
        seedChat(events, b1.id, "tb1", survivor.workspaceId)
        seedWorkspace(doomed.workspaceId)
        seedWorkspace(survivor.workspaceId)

        assertFailsWith<IOException> { projects.delete(doomed.id) }
        assertEquals(listOf(doomed.workspaceId), attempts)
        assertEquals(listOf(doomed.copy(deleting = true), survivor), projects.observeAll().first())
        // Chat-owned data already went; the workspace and metadata remain for the retry.
        assertEquals(emptyList(), events.history(a1.id))
        assertFalse(blobDirectory(a1.id).exists())
        workspaceTree(doomed.workspaceId).forEach { assertTrue(it.exists()) }
        assertEquals(1, first.dao().taskCount())
        first.close()

        val second = open(file)
        try {
            val reopenedChats = conversationPort(second.dao())
            val reopenedEvents = repository(second.dao())
            val deleted = mutableListOf<String>()
            val reopenedProjects = projectPort(second.dao(), reopenedChats, deleteWorkspace = { id -> deleted += id; removeWorkspace(id) })
            assertEquals(listOf(doomed.copy(deleting = true), survivor), reopenedProjects.observeAll().first())
            assertFalse(reopenedProjects.saveInstructions(doomed.id, "new rules"))
            assertFailsWith<IllegalStateException> { reopenedChats.create("late member", doomed.id) }

            assertTrue(reopenedProjects.delete(doomed.id))
            assertEquals(listOf(doomed.workspaceId), deleted)
            workspaceTree(doomed.workspaceId).forEach { assertFalse(it.exists()) }
            workspaceTree(survivor.workspaceId).forEach { assertTrue(it.exists()) }
            assertEquals(bigResult("tb1"), reopenedEvents.history(b1.id).filterIsInstance<TaskEvent.ToolResult>().single())
            assertEquals(1, blobDirectory(b1.id).walkTopDown().filter { it.isFile }.count())
            assertEquals(listOf(survivor), reopenedProjects.observeAll().first())
        } finally {
            second.close()
        }
    }

    @Test
    fun deletionPassesThePersistedLegacyWorkspaceIdToTheBoundaryVerbatim() = runTest {
        val db = open(folder.newFile("lumynoid.db"))
        val dao = db.dao()
        val chats = conversationPort(dao)
        val events = repository(dao)
        val deleted = mutableListOf<String>()
        val projects = projectPort(dao, chats, deleteWorkspace = { id -> deleted += id; removeWorkspace(id) })
        // An adopted legacy project keeps its historical workspace ID, distinct from its row ID.
        dao.insertProject(ProjectEntity("p-legacy", "Adopted", "legacy-ws", "Legacy rules"))
        dao.insertConversation(ConversationEntity("legacy-chat", 1L, "Legacy chat", "p-legacy"))
        seedChat(events, "legacy-chat", "t-legacy", "legacy-ws")
        seedWorkspace("legacy-ws")
        val other = assertNotNull(projects.create("Other"))
        seedWorkspace(other.workspaceId)

        assertTrue(projects.delete("p-legacy"))

        assertEquals(listOf("legacy-ws"), deleted)
        assertEquals(emptyList(), events.history("legacy-chat"))
        assertFalse(blobDirectory("legacy-chat").exists())
        workspaceTree("legacy-ws").forEach { assertFalse(it.exists()) }
        workspaceTree(other.workspaceId).forEach { assertTrue(it.exists()) }
        assertEquals(listOf(other), projects.observeAll().first())
        assertEquals(0, dao.taskCount())
        db.close()
    }
}