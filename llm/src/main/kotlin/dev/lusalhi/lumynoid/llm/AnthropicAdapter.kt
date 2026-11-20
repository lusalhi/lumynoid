package dev.lusalhi.lumynoid.llm

import dev.lusalhi.lumynoid.core.LlmChunk
import dev.lusalhi.lumynoid.core.LlmError
import dev.lusalhi.lumynoid.core.LlmRequest
import dev.lusalhi.lumynoid.core.ModelListResult
import dev.lusalhi.lumynoid.core.Role
import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Streams answers from the Anthropic Messages API (AD-11), written directly on Ktor with no vendor SDK.
 * Only an API key is used (C10); it travels only in the `x-api-key` header.
 */
class AnthropicAdapter(private val client: HttpClient) : WireAdapter {

    override fun stream(baseUrl: String, model: String, apiKey: String, request: LlmRequest): Flow<LlmChunk> = flow {
        val body = MessagesRequest(
            model = model,
            maxTokens = MAX_TOKENS,
            stream = true,
            // Anthropic takes system text beside the turns, not inside them. Tool turns do not exist yet.
            system = request.messages.filter { it.role == Role.SYSTEM }.joinToString("\n\n") { it.content }.ifBlank { null },
            messages = request.messages.mapNotNull { message ->
                when (message.role) {
                    Role.USER -> Turn("user", message.content)
                    Role.ASSISTANT -> Turn("assistant", message.content)
                    Role.SYSTEM, Role.TOOL -> null
                }
            },
        )
        streamPost(
            client,
            joinUrl(baseUrl, "/messages"),
            wireJson.encodeToString(MessagesRequest.serializer(), body),
            auth = { authenticate(apiKey) },
        ) { readStream(it) }
    }

    override suspend fun models(baseUrl: String, apiKey: String): ModelListResult =
        client.listModelIds(joinUrl(baseUrl, "/models?limit=$MODEL_LIST_LIMIT")) { authenticate(apiKey) }

    private fun HttpRequestBuilder.authenticate(apiKey: String) {
        header("x-api-key", apiKey)
        header("anthropic-version", API_VERSION)
    }

    private suspend fun FlowCollector<LlmChunk>.readStream(response: HttpResponse) {
        var inputTokens = 0
        var outputTokens = 0
        var finished = false
        response.readDataLines { payload ->
            val event = wireJson.decodeFromString(StreamEvent.serializer(), payload)
            when (event.type) {
                "message_start" -> inputTokens = event.message?.usage?.inputTokens ?: inputTokens
                "content_block_delta" ->
                    event.delta?.text?.takeIf { it.isNotEmpty() }?.let { emit(LlmChunk.TextDelta(it)) }
                "message_delta" -> outputTokens = event.usage?.outputTokens ?: outputTokens
                "message_stop" -> {
                    finished = true
                    return@readDataLines false
                }
                "error" -> {
                    emit(LlmChunk.Failed(anthropicError(event.error?.type)))
                    return
                }
                // ping, content_block_start/stop and future events carry nothing we need.
            }
            true
        }
        // A stream that ends without message_stop was cut off: never record it as an answer.
        if (!finished) {
            emit(LlmChunk.Failed(LlmError.Network))
            return
        }
        if (inputTokens > 0 || outputTokens > 0) emit(LlmChunk.Usage(inputTokens, outputTokens))
        emit(LlmChunk.Done)
    }

    private fun anthropicError(type: String?): LlmError = when (type) {
        "authentication_error", "permission_error" -> LlmError.AuthFailed
        "rate_limit_error" -> LlmError.RateLimited(null)
        "billing_error" -> LlmError.QuotaExhausted
        else -> LlmError.Server // overloaded_error, api_error and anything new
    }

    @Serializable
    private class MessagesRequest(
        val model: String,
        @SerialName("max_tokens") val maxTokens: Int,
        val stream: Boolean,
        val system: String? = null,
        val messages: List<Turn>,
    )

    @Serializable
    private class Turn(val role: String, val content: String)

    @Serializable
    private class StreamEvent(
        val type: String,
        val message: StartedMessage? = null,
        val delta: Delta? = null,
        val usage: UsageDto? = null,
        val error: ErrorBody? = null,
    )

    @Serializable
    private class StartedMessage(val usage: UsageDto? = null)

    @Serializable
    private class Delta(val text: String? = null)

    @Serializable
    private class UsageDto(
        @SerialName("input_tokens") val inputTokens: Int? = null,
        @SerialName("output_tokens") val outputTokens: Int? = null,
    )

    @Serializable
    private class ErrorBody(val type: String? = null)

    private companion object {
        const val API_VERSION = "2023-06-01"

        /** Messages requires `max_tokens`; a reply that reaches it still ends in `message_stop`. */
        const val MAX_TOKENS = 8192
        const val MODEL_LIST_LIMIT = 1000
    }
}
