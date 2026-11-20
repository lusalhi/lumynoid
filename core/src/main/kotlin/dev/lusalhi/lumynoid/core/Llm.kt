package dev.lusalhi.lumynoid.core

import kotlinx.coroutines.flow.Flow

enum class Role { SYSTEM, USER, ASSISTANT, TOOL }

data class LlmMessage(
    val role: Role,
    val content: String,
    val toolCalls: List<ToolCall> = emptyList(),
    val toolCallId: String? = null,
)

data class LlmRequest(val messages: List<LlmMessage>, val tools: List<ToolDefinition> = emptyList())

/** Errors normalised by the adapters (AD-11). They travel as [LlmChunk.Failed], never as exceptions. */
sealed interface LlmError {
    data class RateLimited(val retryAfterMillis: Long?) : LlmError
    data object QuotaExhausted : LlmError
    data object AuthFailed : LlmError
    data object ToolsUnsupported : LlmError
    data object MalformedToolCall : LlmError
    data object Network : LlmError
    data object Server : LlmError
}

sealed interface LlmChunk {
    data class Routed(val providerId: String, val modelId: String) : LlmChunk
    data class TextDelta(val text: String) : LlmChunk
    /** The index groups fragments within this assistant reply; it is not a durable call ID. */
    data class ToolCallDelta(
        val index: Int,
        val nameFragment: String? = null,
        val argumentsFragment: String? = null,
    ) : LlmChunk
    data class Usage(val promptTokens: Int, val completionTokens: Int) : LlmChunk
    data object Done : LlmChunk

    /** Terminal: the stream failed. Collectors stop after this chunk. */
    data class Failed(val error: LlmError) : LlmChunk
}

/** The only door to a language model. UI and schedulers never call it (AD-2). */
fun interface LlmPort {
    fun stream(request: LlmRequest): Flow<LlmChunk>
}
