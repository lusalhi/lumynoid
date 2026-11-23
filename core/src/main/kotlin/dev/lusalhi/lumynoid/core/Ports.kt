package dev.lusalhi.lumynoid.core

import kotlinx.coroutines.flow.Flow

/** Opaque handle to a secret; modules other than `:llm` never see the value (AD-15). */
@JvmInline
value class SecretRef(val value: String)

interface SecretPort {
    suspend fun put(ref: SecretRef, secret: String)
    suspend fun get(ref: SecretRef): String?
}

/** Which wire format a provider speaks. The persisted names never change. */
enum class ProviderKind(val wireName: String) {
    OPENAI_COMPATIBLE("openai_compatible"),
    ANTHROPIC("anthropic"),
    ;

    companion object {
        /** Unknown names fall back to OpenAI-compatible, the format every pre-v3 provider uses. */
        fun fromWireName(name: String?): ProviderKind = entries.firstOrNull { it.wireName == name } ?: OPENAI_COMPATIBLE
    }
}

/** One saved provider. The key itself lives behind [apiKey] in the secret store (AD-15). */
data class ProviderConfig(
    val id: String,
    val name: String,
    val presetId: String?,
    val baseUrl: String,
    val apiKey: SecretRef,
    val kind: ProviderKind = ProviderKind.OPENAI_COMPATIBLE,
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
    val kind: ProviderKind = ProviderKind.OPENAI_COMPATIBLE,
)

/** A saved provider as the UI may see it: never the key. */
data class ProviderSummary(
    val id: String,
    val name: String,
    val presetId: String?,
    val baseUrl: String,
    val models: List<String>,
    val kind: ProviderKind = ProviderKind.OPENAI_COMPATIBLE,
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

/** [title] is empty until a message names it; the UI then shows a placeholder. */
data class Conversation(
    val id: String,
    val title: String,
    val createdAt: Long,
    val lastActivityAt: Long,
    val projectId: String? = null,
)

data class Project(
    val id: String,
    val name: String,
    val workspaceId: String,
    val instructions: String,
    val deleting: Boolean = false,
)

interface ProjectPort {
    /** Returns null for a blank name. */
    suspend fun create(name: String): Project?
    fun observeAll(): Flow<List<Project>>
    suspend fun saveInstructions(id: String, instructions: String): Boolean

    /**
     * Removes the project and its owned data, or resumes an interrupted deletion.
     * Returns false while a member has unfinished work or cleanup is already running.
     */
    suspend fun delete(id: String): Boolean

    /** Stored instructions of the active project owning [workspaceId]; null when unavailable. */
    suspend fun instructionsForWorkspace(workspaceId: String): String?

    companion object {
        /** The shared storage limit, counted in Unicode code points. */
        const val MAX_INSTRUCTION_CODE_POINTS = 2_000
    }
}

sealed interface ConversationWorkspace {
    data object Plain : ConversationWorkspace
    data class Project(val workspaceId: String) : ConversationWorkspace
}

/** The UI's only way to read and write conversations (AD-23). Messages are written by `TaskRunner` alone. */
interface ConversationPort {
    /** Creates a conversation titled from [firstMessage], optionally belonging to a project. */
    suspend fun create(firstMessage: String, projectId: String? = null): Conversation

    /** Null means the conversation is missing or its project scope is unavailable, not a plain chat. */
    suspend fun workspace(id: String): ConversationWorkspace?

    /** The conversation with the most recent activity. */
    suspend fun latest(): Conversation?

    /** All conversations, most recent activity first. */
    fun observeAll(): Flow<List<Conversation>>

    /** Returns false and keeps the old title when [title] is blank. */
    suspend fun rename(id: String, title: String): Boolean

    /** Removes the conversation and its owned data. Returns false while it has an unfinished task. */
    suspend fun delete(id: String): Boolean
}

data class TaskRequest(
    val origin: Origin,
    val conversationId: String,
    val input: String,
)

const val DEFAULT_MAX_LLM_CALLS = 20

/** App-wide limits applied when a task is submitted. */
interface TaskSettings {
    suspend fun maxLlmCalls(): Int

    /** Saves a positive limit and returns whether the write succeeded. */
    suspend fun saveMaxLlmCalls(limit: Int): Boolean
}

enum class ApprovalDecision { APPROVE, DENY }

interface ApprovalPort {
    /** Returns true when this decision was recorded for a still-pending, unexpired request. */
    suspend fun resolve(approvalId: String, decision: ApprovalDecision): Boolean
}

/** A task that has no terminal event yet: a crash or kill cut it off, or it never started. */
data class UnfinishedTask(val taskId: String, val conversationId: String)

/** Event log storage. Only [TaskRunner] may call the write functions (AD-4, AD-5). */
interface EventRepository {
    suspend fun createTask(taskId: String, request: TaskRequest, at: Long, workspaceId: String?)
    /** Tool, approval and retry audit events must be stored before returning; throw if the conversation is gone. */
    suspend fun append(conversationId: String, event: TaskEvent)
    fun observe(conversationId: String): Flow<List<TaskEvent>>

    /** Read-only snapshot of the conversation's events in log order, for building context. */
    suspend fun history(conversationId: String): List<TaskEvent>

    /**
     * Tasks with no events or whose last event is nonterminal ([TaskEvent.needsRecovery]). Finished, failed,
     * cancelled and [TaskEvent.Unknown]-ending tasks are not listed.
     */
    suspend fun unfinishedTasks(): List<UnfinishedTask>
}
