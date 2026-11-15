package dev.lusalhi.lumynoid.data

import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import dev.lusalhi.lumynoid.core.ActiveModel
import dev.lusalhi.lumynoid.core.Clock
import dev.lusalhi.lumynoid.core.ModelProfile
import dev.lusalhi.lumynoid.core.Origin
import dev.lusalhi.lumynoid.core.ProviderConfig
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
        val conversation = RoomConversationPort(first.dao(), UuidV7, Clock { 10L }).create()
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
        store.save(ProviderConfig("p2", "Two", null, "https://two.test/v1", SecretRef("k2")))
        store.saveModel(ModelProfile("p1", "a"))
        store.saveModel(ModelProfile("p1", "b"))
        store.saveModel(ModelProfile("p1", "a")) // saved again: now the most recent
        store.setActive(ActiveModel("p1", "b"))
        store.setActive(ActiveModel("p2", "z"))

        assertEquals(listOf("p1", "p2"), store.providers().map { it.id })
        assertEquals("openrouter", store.provider("p1")?.presetId)
        assertEquals(listOf("b", "a"), store.models("p1").map { it.modelId })
        assertEquals(ActiveModel("p2", "z"), store.active())
        db.close()
    }
}
