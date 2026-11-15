package dev.lusalhi.lumynoid.data

import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import dev.lusalhi.lumynoid.core.ActiveModel
import dev.lusalhi.lumynoid.core.Clock
import dev.lusalhi.lumynoid.core.ModelProfile
import dev.lusalhi.lumynoid.core.Origin
import dev.lusalhi.lumynoid.core.ProviderConfig
import dev.lusalhi.lumynoid.core.ProviderKind
import dev.lusalhi.lumynoid.core.SecretRef
import dev.lusalhi.lumynoid.core.TaskEvent
import dev.lusalhi.lumynoid.core.TaskRequest
import dev.lusalhi.lumynoid.core.UuidV7
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull

/** Runs the real Room schema on a real SQLite file, closing and reopening it like a process restart. */
class PersistenceTest {

    @get:Rule
    val folder = TemporaryFolder()

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
        val events = RoomEventRepository(first.dao())
        events.createTask("t1", request(conversation.id), 10L)
        events.append(conversation.id, TaskEvent.UserMessage("e1", "t1", 10L, Origin.User, "hello"))
        events.append(conversation.id, TaskEvent.AssistantMessage("e2", "t1", 11L, "world"))
        events.append(conversation.id, TaskEvent.TaskFinished("e3", "t1", 12L))
        first.close()

        val second = open(file)
        val reopened = RoomEventRepository(second.dao()).observe(conversation.id).first()
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

        val read = RoomEventRepository(dao).observe("c").first()

        assertEquals(2, read.size)
        read.forEach { assertIs<TaskEvent.Unknown>(it) }
        db.close()
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
        val events = RoomEventRepository(db.dao())
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
        val events = RoomEventRepository(db.dao())
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
        val events = RoomEventRepository(db.dao())
        val c = port.create("gone")
        port.delete(c.id)

        events.createTask("t1", TaskRequest(Origin.User, c.id, "ws", "x"), 2L)
        events.append(c.id, TaskEvent.UserMessage("e1", "t1", 2L, Origin.User, "x"))

        assertEquals(0, db.dao().taskCount())
        assertEquals(emptyList(), events.history(c.id))
        db.close()
    }
}
