package dev.lusalhi.lumynoid.data

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.Index
import androidx.room3.PrimaryKey

@Entity(tableName = "conversation")
internal data class ConversationEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)

@Entity(tableName = "task")
internal data class TaskEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "conversation_id") val conversationId: String,
    @ColumnInfo(name = "workspace_id") val workspaceId: String,
    val origin: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)

/** One row per [dev.lusalhi.lumynoid.core.TaskEvent]; `payload` is the versioned JSON (AD-19). */
@Entity(
    tableName = "task_event",
    indices = [Index("conversation_id", "seq"), Index("id", unique = true)],
)
internal data class TaskEventEntity(
    @PrimaryKey(autoGenerate = true) val seq: Long = 0,
    val id: String,
    @ColumnInfo(name = "task_id") val taskId: String,
    @ColumnInfo(name = "conversation_id") val conversationId: String,
    val at: Long,
    val payload: String,
)

@Entity(tableName = "provider_config")
internal data class ProviderConfigEntity(
    @PrimaryKey val id: String,
    val name: String,
    @ColumnInfo(name = "preset_id") val presetId: String?,
    @ColumnInfo(name = "base_url") val baseUrl: String,
    @ColumnInfo(name = "secret_ref") val secretRef: String,
)

@Entity(tableName = "model_profile", primaryKeys = ["provider_id", "model_id"])
internal data class ModelProfileEntity(
    @ColumnInfo(name = "provider_id") val providerId: String,
    @ColumnInfo(name = "model_id") val modelId: String,
    @ColumnInfo(name = "supports_tools") val supportsTools: Boolean?,
)

/** At most one row (`id = 1`): the provider and model every message uses. */
@Entity(tableName = "active_model")
internal data class ActiveModelEntity(
    @PrimaryKey val id: Int = 1,
    @ColumnInfo(name = "provider_id") val providerId: String,
    @ColumnInfo(name = "model_id") val modelId: String,
)
