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

    @Query("SELECT * FROM provider_config LIMIT 1")
    suspend fun activeProvider(): ProviderConfigEntity?
}
