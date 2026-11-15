package dev.lusalhi.lumynoid.data

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import kotlinx.coroutines.flow.Flow

@Dao
internal interface LumynoidDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertConversation(conversation: ConversationEntity)

    @Query("SELECT * FROM conversation ORDER BY created_at DESC, id DESC LIMIT 1")
    suspend fun latestConversation(): ConversationEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertTask(task: TaskEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertEvent(event: TaskEventEntity)

    @Query("SELECT * FROM task_event WHERE conversation_id = :conversationId ORDER BY seq")
    fun observeEvents(conversationId: String): Flow<List<TaskEventEntity>>

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
