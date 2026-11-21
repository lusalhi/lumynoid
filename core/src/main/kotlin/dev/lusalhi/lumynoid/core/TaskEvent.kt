package dev.lusalhi.lumynoid.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic

/** Where a task came from (AD-23). Only user messages exist in this story. */
@Serializable
sealed interface Origin {
    @Serializable
    @SerialName("user")
    data object User : Origin
}

/** Why a task ended in failure. Stored by name; the UI maps each to a plain-language string. */
@Serializable
enum class FailureReason {
    @SerialName("auth_failed") AUTH_FAILED,
    @SerialName("rate_limited") RATE_LIMITED,
    @SerialName("quota_exhausted") QUOTA_EXHAUSTED,
    @SerialName("network") NETWORK,
    @SerialName("server") SERVER,
    @SerialName("interrupted") INTERRUPTED,
    @SerialName("tool_denied") TOOL_DENIED,
    @SerialName("approval_denied") APPROVAL_DENIED,
    @SerialName("approval_expired") APPROVAL_EXPIRED,
    @SerialName("approval_changed") APPROVAL_CHANGED,
    @SerialName("malformed_tool_call") MALFORMED_TOOL_CALL,
    @SerialName("budget_exhausted") BUDGET_EXHAUSTED,
}

@Serializable
data class ToolApproval(
    val id: String,
    val call: TaskEvent.RequestedToolCall,
    val risk: RiskAssessment,
    val expiresAt: Long,
)

@Serializable
enum class ApprovalResolution {
    @SerialName("approved") APPROVED,
    @SerialName("denied") DENIED,
    @SerialName("expired") EXPIRED,
    @SerialName("cancelled") CANCELLED,
}

/**
 * The append-only record of everything a task did (AD-4). Only [TaskRunner] appends.
 * Persisted as versioned JSON; subtypes this build does not know decode to [Unknown].
 */
@Serializable
sealed class TaskEvent {
    abstract val id: String
    abstract val taskId: String
    abstract val at: Long

    val needsRecovery: Boolean
        get() = when (this) {
            is TaskQueued, is UserMessage, is AssistantMessage, is ToolCallRequested, is ToolResult,
            is ApprovalRequested, is ApprovalResolved, is ToolCallRejected, is LlmRetry -> true
            is TaskFinished, is TaskFailed, is TaskCancelled, is Unknown -> false
        }

    @Serializable
    @SerialName("task_queued")
    data class TaskQueued(
        override val id: String,
        override val taskId: String,
        override val at: Long,
    ) : TaskEvent()

    @Serializable
    @SerialName("user_message")
    data class UserMessage(
        override val id: String,
        override val taskId: String,
        override val at: Long,
        val origin: Origin,
        val text: String,
    ) : TaskEvent()

    @Serializable
    @SerialName("assistant_message")
    data class AssistantMessage(
        override val id: String,
        override val taskId: String,
        override val at: Long,
        val text: String,
    ) : TaskEvent()

    @Serializable
    data class RequestedToolCall(val call: ToolCall, val description: UiText)

    /** One assistant reply can request several calls; each result carries its own call ID. */
    @Serializable
    @SerialName("tool_call_requested")
    data class ToolCallRequested(
        override val id: String,
        override val taskId: String,
        override val at: Long,
        val assistantText: String,
        val calls: List<RequestedToolCall>,
    ) : TaskEvent()

    @Serializable
    @SerialName("tool_result")
    data class ToolResult(
        override val id: String,
        override val taskId: String,
        override val at: Long,
        val callId: String,
        val outcome: ToolOutcome,
    ) : TaskEvent()

    /** A malformed assistant reply was discarded. [callNumber] is its one-based LLM invocation number. */
    @Serializable
    @SerialName("tool_call_rejected")
    data class ToolCallRejected(
        override val id: String,
        override val taskId: String,
        override val at: Long,
        val callNumber: Int,
    ) : TaskEvent()

    /** A repair request is about to be sent as invocation [callNumber]. */
    @Serializable
    @SerialName("llm_retry")
    data class LlmRetry(
        override val id: String,
        override val taskId: String,
        override val at: Long,
        val callNumber: Int,
    ) : TaskEvent()

    @Serializable
    @SerialName("approval_requested")
    data class ApprovalRequested(
        override val id: String,
        override val taskId: String,
        override val at: Long,
        val approval: ToolApproval,
    ) : TaskEvent()

    @Serializable
    @SerialName("approval_resolved")
    data class ApprovalResolved(
        override val id: String,
        override val taskId: String,
        override val at: Long,
        val approvalId: String,
        val callId: String,
        val decision: ApprovalResolution,
    ) : TaskEvent()

    @Serializable
    @SerialName("task_finished")
    data class TaskFinished(
        override val id: String,
        override val taskId: String,
        override val at: Long,
    ) : TaskEvent()

    @Serializable
    @SerialName("task_failed")
    data class TaskFailed(
        override val id: String,
        override val taskId: String,
        override val at: Long,
        val reason: FailureReason,
    ) : TaskEvent()

    @Serializable
    @SerialName("task_cancelled")
    data class TaskCancelled(
        override val id: String,
        override val taskId: String,
        override val at: Long,
    ) : TaskEvent()

    /** Any event type written by a different build; never crashes a reader. */
    @Serializable
    @SerialName("unknown")
    data class Unknown(
        override val id: String = "",
        override val taskId: String = "",
        override val at: Long = 0L,
    ) : TaskEvent()
}

object TaskEventJson {
    val json: Json = Json {
        classDiscriminator = "type"
        ignoreUnknownKeys = true
        serializersModule = SerializersModule {
            polymorphic(TaskEvent::class) {
                defaultDeserializer { TaskEvent.Unknown.serializer() }
            }
        }
    }

    fun encode(event: TaskEvent): String = json.encodeToString(TaskEvent.serializer(), event)

    fun decode(text: String): TaskEvent = json.decodeFromString(TaskEvent.serializer(), text)
}
