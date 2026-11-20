package dev.lusalhi.lumynoid.data

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import androidx.room3.Transaction
import kotlinx.coroutines.flow.Flow

/** Most recent activity first; shared by `latest()` and the observed list. */
private const val CONVERSATION_ROWS =
    "SELECT c.id AS id, c.title AS title, c.created_at AS created_at, " +
        "COALESCE((SELECT MAX(e.at) FROM task_event e WHERE e.conversation_id = c.id), c.created_at) AS last_activity_at " +
        "FROM conversation c ORDER BY last_activity_at DESC, c.id DESC"

@Dao
internal interface LumynoidDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertConversation(conversation: ConversationEntity)

    @Query("$CONVERSATION_ROWS LIMIT 1")
    suspend fun latestConversation(): ConversationRow?

    @Query(CONVERSATION_ROWS)
    fun observeConversations(): Flow<List<ConversationRow>>

    @Query("UPDATE conversation SET title = :title WHERE id = :id")
    suspend fun setTitle(id: String, title: String): Int

    @Query("DELETE FROM task_event WHERE conversation_id = :id")
    suspend fun deleteEvents(id: String)

    @Query("DELETE FROM task WHERE conversation_id = :id")
    suspend fun deleteTasks(id: String)

    @Query("DELETE FROM conversation WHERE id = :id")
    suspend fun deleteConversationRow(id: String)

    /** Events, tasks and the conversation go together or not at all. */
    @Transaction
    suspend fun deleteConversation(id: String) {
        deleteEvents(id)
        deleteTasks(id)
        deleteConversationRow(id)
    }

    @Query("SELECT COUNT(*) FROM task")
    suspend fun taskCount(): Int

    /** Writes only while the conversation row exists, atomically against [deleteConversation]. */
    @Query(
        "INSERT INTO task (id, conversation_id, workspace_id, origin, created_at) " +
            "SELECT :id, :conversationId, :workspaceId, :origin, :createdAt " +
            "WHERE EXISTS (SELECT 1 FROM conversation WHERE id = :conversationId)",
    )
    suspend fun insertTaskIfConversationExists(id: String, conversationId: String, workspaceId: String, origin: String, createdAt: Long)

    /** Writes only while the conversation row exists, atomically against [deleteConversation]. */
    @Query(
        "INSERT INTO task_event (id, task_id, conversation_id, at, payload) " +
            "SELECT :id, :taskId, :conversationId, :at, :payload " +
            "WHERE EXISTS (SELECT 1 FROM conversation WHERE id = :conversationId)",
    )
    suspend fun insertEventIfConversationExists(id: String, taskId: String, conversationId: String, at: Long, payload: String): Long

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertEvent(event: TaskEventEntity)

    @Query("SELECT * FROM task_event WHERE conversation_id = :conversationId ORDER BY seq")
    suspend fun events(conversationId: String): List<TaskEventEntity>

    @Query("SELECT * FROM task_event WHERE conversation_id = :conversationId ORDER BY seq")
    fun observeEvents(conversationId: String): Flow<List<TaskEventEntity>>

    /** Every task with the payload of its last event (null when it has none). */
    @Query(
        "SELECT t.id AS task_id, t.conversation_id AS conversation_id, " +
            "(SELECT e.payload FROM task_event e WHERE e.conversation_id = t.conversation_id AND e.task_id = t.id " +
            "ORDER BY e.seq DESC LIMIT 1) AS last_payload FROM task t",
    )
    suspend fun taskLastEvents(): List<TaskLastEventRow>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertProvider(config: ProviderConfigEntity)

    @Query("SELECT * FROM provider_config ORDER BY rowid")
    suspend fun providers(): List<ProviderConfigEntity>

    @Query("SELECT * FROM provider_config WHERE id = :id")
    suspend fun provider(id: String): ProviderConfigEntity?

    /** REPLACE re-inserts the row, so `rowid` order is the order models were last saved. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertModel(model: ModelProfileEntity)

    @Query("SELECT * FROM model_profile WHERE provider_id = :providerId ORDER BY rowid")
    suspend fun models(providerId: String): List<ModelProfileEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertActive(active: ActiveModelEntity)

    @Query("SELECT * FROM active_model WHERE id = 1")
    suspend fun active(): ActiveModelEntity?
}
