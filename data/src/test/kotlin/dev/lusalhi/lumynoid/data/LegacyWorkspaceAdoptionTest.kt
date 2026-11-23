package dev.lusalhi.lumynoid.data

import androidx.room3.Room
import androidx.room3.testing.MigrationTestHelper
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import dev.lusalhi.lumynoid.core.Clock
import dev.lusalhi.lumynoid.core.ConversationWorkspace
import dev.lusalhi.lumynoid.core.LlmPort
import dev.lusalhi.lumynoid.core.Origin
import dev.lusalhi.lumynoid.core.TaskEvent
import dev.lusalhi.lumynoid.core.TaskEventJson
import dev.lusalhi.lumynoid.core.TaskRequest
import dev.lusalhi.lumynoid.core.TaskRunner
import dev.lusalhi.lumynoid.core.ToolOutcome
import dev.lusalhi.lumynoid.core.UuidV7
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LegacyWorkspaceAdoptionTest {
    @get:Rule val files = TemporaryFolder()

    private val databaseFile get() = File(files.root, "lumynoid.db")
    private val blobs get() = EventBlobStore(File(files.root, "event-blobs"))

    private suspend fun baseline(version: Int) = MigrationTestHelper(
        schemaDirectoryPath = Path.of("schemas"),
        databasePath = databaseFile.toPath(),
        driver = BundledSQLiteDriver(),
        databaseClass = LumynoidDatabase::class,
        databaseFactory = { LumynoidDatabase_Impl() },
    ).createDatabase(version)

    private fun open() = Room.databaseBuilder<LumynoidDatabase>(databaseFile.path) { LumynoidDatabase_Impl() }
        .setDriver(BundledSQLiteDriver())
        .addMigrations(MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7)
        .build()

    private fun conversations(db: LumynoidDatabase) =
        RoomConversationPort(db.dao(), UuidV7, Clock { 10L }, blobs, { action -> action(emptySet()) }) { _, delete -> delete(); true }

    private fun projects(db: LumynoidDatabase, chats: RoomConversationPort) =
        RoomProjectPort(db.dao(), UuidV7, chats, { action -> action(emptySet()) }) { }

    @Test
    fun baselineUpgradeSurvivesReadFailureAndInterruptionWithoutLosingChatData() = runTest {
        val file = File(files.root, "workspaces/a-file/notes.txt").apply { parentFile.mkdirs(); writeText("saved notes") }
        val module = File(files.root, "coding-installs/b-package/digest/index.js").apply { parentFile.mkdirs(); writeText("saved package") }
        val untitled = File(files.root, "workspaces/d-untitled/draft.txt").apply { parentFile.mkdirs(); writeText("saved draft") }
        assertTrue(File(files.root, "workspaces/c-empty").mkdir())
        val event = TaskEvent.ToolResult("e1", "t1", 3L, "call", ToolOutcome("x".repeat(270_000)))
        val json = TaskEventJson.encode(event)
        val envelope = StoredEventBlob.encode(blobs.write("a-file", json.encodeToByteArray()), event, json)
        val terminal = TaskEventJson.encode(TaskEvent.TaskFinished("e2", "t1", 4L))
        baseline(4).use { v4 ->
            v4.execSQL("INSERT INTO conversation (id, created_at, title) VALUES " +
                "('a-file', 1, 'Old title'), ('b-package', 1, 'Old title'), ('c-empty', 1, 'Plain'), ('d-untitled', 1, '')")
            v4.execSQL("INSERT INTO task (id, conversation_id, workspace_id, origin, created_at) VALUES ('t1', 'a-file', 'a-file', 'user', 2)")
            v4.prepare("INSERT INTO task_event (id, task_id, conversation_id, at, payload) VALUES (?, 't1', 'a-file', ?, ?)").use { st ->
                for ((id, at, payload) in listOf(Triple("e1", 3L, envelope), Triple("e2", 4L, terminal))) {
                    st.bindText(1, id)
                    st.bindLong(2, at)
                    st.bindText(3, payload)
                    st.step()
                    st.reset()
                }
            }
        }

        val first = open()
        try {
            val paused = CompletableDeferred<Unit>()
            val migration = launch {
                conversations(first).adoptLegacyWorkspaces("Untitled Project") { id ->
                    when (id) {
                        "a-file" -> null // First root inspection failed.
                        "b-package" -> module.isFile
                        "c-empty" -> false
                        "d-untitled" -> { paused.complete(Unit); awaitCancellation() }
                        else -> error("Unexpected adoption candidate")
                    }
                }
            }
            paused.await()
            val reader = open()
            try {
                val readerChats = conversations(reader)
                val readerEvents = RoomEventRepository(reader.dao(), blobs)
                assertNull(readerChats.workspace("a-file")) // Independent reader used by preview.
                assertNull(readerChats.workspace("d-untitled"))
                assertEquals(ConversationWorkspace.Project("b-package"), readerChats.workspace("b-package"))
                assertEquals(ConversationWorkspace.Plain, readerChats.workspace("c-empty"))
                assertFalse(readerChats.delete("a-file"))
                val runner = TaskRunner(LlmPort { error("Pending chat reached provider") }, readerEvents, backgroundScope,
                    resolveWorkspace = readerChats::workspace)
                assertFailsWith<IllegalStateException> { runner.submit(TaskRequest(Origin.User, "a-file", "not admitted")) }
                assertEquals(1, reader.dao().taskCount())
                assertEquals(listOf("e1", "e2"), readerEvents.history("a-file").map { it.id })
                assertEquals(event, readerEvents.event("a-file", "e1"))
            } finally {
                reader.close()
                migration.cancelAndJoin()
            }
        } finally {
            first.close()
        }

        val reopened = open()
        try {
            val chats = conversations(reopened)
            assertNull(chats.workspace("a-file"))
            assertNull(chats.workspace("d-untitled"))
            assertEquals(ConversationWorkspace.Plain, chats.workspace(chats.create("fresh chat").id))
            chats.adoptLegacyWorkspaces("Untitled Project") { id ->
                when (id) {
                    "a-file" -> file.isFile
                    "d-untitled" -> untitled.isFile
                    else -> error("Probed a completed or new chat: $id")
                }
            }
            val adopted = projects(reopened, chats).observeAll().first()
            assertEquals(
                mapOf("a-file" to "Old title", "b-package" to "Old title", "d-untitled" to "Untitled Project"),
                adopted.associate { it.workspaceId to it.name },
            )
            val membership = chats.observeAll().first().associate { it.id to it.projectId }
            for (project in adopted) {
                assertEquals(project.id, membership[project.workspaceId])
                assertEquals(ConversationWorkspace.Project(project.workspaceId), chats.workspace(project.workspaceId))
            }
            assertNull(membership.getValue("c-empty"))
            chats.adoptLegacyWorkspaces("Other title") { error("Adoption ran after completion: $it") }
            assertEquals(envelope, reopened.dao().event("a-file", "e1")?.payload)
            assertEquals(event, RoomEventRepository(reopened.dao(), blobs).event("a-file", "e1"))
            assertEquals("saved notes", file.readText())
            assertEquals("saved package", module.readText())
            assertEquals("saved draft", untitled.readText())
            assertFalse(File(files.root, "workspaces/b-package").exists())
        } finally {
            reopened.close()
        }
        BundledSQLiteDriver().open(databaseFile.path).use { db ->
            db.prepare("SELECT id, conversation_id, workspace_id FROM task").use { st ->
                assertTrue(st.step())
                assertEquals(listOf("t1", "a-file", "a-file"), (0..2).map(st::getText))
                assertFalse(st.step())
            }
        }
    }

    @Test
    fun upgradingFromV5DoesNotProbeOrRewriteAnEstablishedProjectMember() = runTest {
        baseline(5).use { v5 ->
            v5.execSQL("INSERT INTO project (id, name, workspace_id, instructions) VALUES ('p', 'Saved project', 'scope', 'Saved instructions')")
            v5.execSQL("INSERT INTO conversation (id, created_at, title, project_id) VALUES " +
                "('member', 1, 'Member', 'p'), ('unassigned', 2, 'Ordinary', NULL)")
        }
        val db = open()
        try {
            val chats = conversations(db)
            assertEquals(ConversationWorkspace.Project("scope"), chats.workspace("member"))
            assertNull(chats.workspace("unassigned"))
            chats.adoptLegacyWorkspaces("Untitled Project") { id ->
                assertEquals("unassigned", id)
                false
            }
            assertEquals(ConversationWorkspace.Plain, chats.workspace("unassigned"))
            assertEquals("Saved instructions", projects(db, chats).observeAll().first().single().instructions)
        } finally {
            db.close()
        }
    }
}
