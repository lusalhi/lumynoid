package dev.lusalhi.lumynoid.core

import kotlinx.coroutines.flow.Flow

/** Opaque handle to a secret; modules other than `:llm` never see the value (AD-15). */
@JvmInline
value class SecretRef(val value: String)

interface SecretPort {
    suspend fun put(ref: SecretRef, secret: String)
    suspend fun get(ref: SecretRef): String?
}

data class ProviderConfig(
    val id: String,
    val baseUrl: String,
    val model: String,
    val apiKey: SecretRef,
)

/** Persistence of the single active provider; owned by `:llm`, stored by `:data`. */
interface ProviderConfigStore {
    suspend fun active(): ProviderConfig?
    suspend fun save(config: ProviderConfig)
}

data class ProviderInput(val baseUrl: String, val apiKey: String, val model: String)

enum class ProviderSaveResult { SAVED, INCOMPLETE }

/** The UI's only way to write provider settings (AD-23). */
interface ProviderAdminPort {
    suspend fun current(): ProviderInput?

    /** Saves the provider, or returns [ProviderSaveResult.INCOMPLETE] when a required field is blank. */
    suspend fun save(input: ProviderInput): ProviderSaveResult
}

data class Conversation(val id: String, val createdAt: Long)

interface ConversationPort {
    suspend fun create(): Conversation
    suspend fun latest(): Conversation?
}

data class TaskRequest(
    val origin: Origin,
    val conversationId: String,
    val workspaceId: String,
    val input: String,
)

/** Event log storage. Only [TaskRunner] may call the write functions (AD-4, AD-5). */
interface EventRepository {
    suspend fun createTask(taskId: String, request: TaskRequest, at: Long)
    suspend fun append(conversationId: String, event: TaskEvent)
    fun observe(conversationId: String): Flow<List<TaskEvent>>
}
