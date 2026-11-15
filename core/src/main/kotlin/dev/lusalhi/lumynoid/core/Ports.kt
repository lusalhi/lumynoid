package dev.lusalhi.lumynoid.core

import kotlinx.coroutines.flow.Flow

/** Opaque handle to a secret; modules other than `:llm` never see the value (AD-15). */
@JvmInline
value class SecretRef(val value: String)

interface SecretPort {
    suspend fun put(ref: SecretRef, secret: String)
    suspend fun get(ref: SecretRef): String?
}

/** One saved provider. The key itself lives behind [apiKey] in the secret store (AD-15). */
data class ProviderConfig(
    val id: String,
    val name: String,
    val presetId: String?,
    val baseUrl: String,
    val apiKey: SecretRef,
)

/** A model a provider offers. [supportsTools] stays unknown (null) until epic-hemat-kuota fills it. */
data class ModelProfile(val providerId: String, val modelId: String, val supportsTools: Boolean? = null)

/** The one provider and model every message uses, app-wide. */
data class ActiveModel(val providerId: String, val modelId: String)

/** Persistence of providers, their models and the active choice; owned by `:llm`, stored by `:data`. */
interface ProviderConfigStore {
    suspend fun providers(): List<ProviderConfig>
    suspend fun provider(id: String): ProviderConfig?
    suspend fun save(config: ProviderConfig)

    /** Models of [providerId] in the order they were last saved, oldest first. */
    suspend fun models(providerId: String): List<ModelProfile>
    suspend fun saveModel(model: ModelProfile)
    suspend fun active(): ActiveModel?
    suspend fun setActive(active: ActiveModel)
}

/** What the user typed on the provider form. A blank [apiKey] on a saved provider means "keep the stored key". */
data class ProviderDraft(
    val id: String?,
    val presetId: String?,
    val name: String,
    val baseUrl: String,
    val apiKey: String,
    val model: String,
    val makeActive: Boolean = false,
)

/** A saved provider as the UI may see it: never the key. */
data class ProviderSummary(
    val id: String,
    val name: String,
    val presetId: String?,
    val baseUrl: String,
    val models: List<String>,
)

data class ProviderOverview(val providers: List<ProviderSummary>, val active: ActiveModel?)

enum class ProviderSaveResult { SAVED, INCOMPLETE }

sealed interface ModelListResult {
    data class Models(val ids: List<String>) : ModelListResult

    /** No usable list (error, empty, unsupported, or incomplete input): the UI falls back to typing the model. */
    data class Unavailable(val error: LlmError?) : ModelListResult
}

sealed interface ConnectionTestResult {
    data object Success : ConnectionTestResult
    data class Failure(val error: LlmError) : ConnectionTestResult

    /** URL, model or key is missing, so nothing was sent. */
    data object Incomplete : ConnectionTestResult
}

/** The UI's only way to read and write provider settings (AD-23). */
interface ProviderAdminPort {
    suspend fun overview(): ProviderOverview

    /** Saves the provider and its model, or returns [ProviderSaveResult.INCOMPLETE] when a required field is blank. */
    suspend fun save(draft: ProviderDraft): ProviderSaveResult

    suspend fun listModels(draft: ProviderDraft): ModelListResult

    /** Sends one tiny request with the draft's settings; nothing is saved. */
    suspend fun testConnection(draft: ProviderDraft): ConnectionTestResult

    /** Makes [modelId] of [providerId] the app-wide choice from the next message on. */
    suspend fun select(providerId: String, modelId: String)
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
