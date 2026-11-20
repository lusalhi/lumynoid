package dev.lusalhi.lumynoid.llm

import dev.lusalhi.lumynoid.core.LlmChunk
import dev.lusalhi.lumynoid.core.LlmError
import dev.lusalhi.lumynoid.core.LlmRequest
import dev.lusalhi.lumynoid.core.ModelListResult
import dev.lusalhi.lumynoid.core.Role
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpHeaders
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * Streams chat completions from any OpenAI Chat Completions-compatible endpoint (AD-11), written
 * directly on Ktor with no vendor SDK. The API key travels only in the `Authorization` header.
 */
class OpenAiCompatibleAdapter(private val client: HttpClient) : WireAdapter {

    override fun stream(baseUrl: String, model: String, apiKey: String, request: LlmRequest): Flow<LlmChunk> = flow {
        val body = ChatRequest(
            model = model,
            messages = request.messages.map { ChatMessage(it.role.wire(), it.content) },
            stream = true,
        )
        streamPost(
            client,
            joinUrl(baseUrl, "/chat/completions"),
            wireJson.encodeToString(ChatRequest.serializer(), body),
            auth = { header(HttpHeaders.Authorization, "Bearer $apiKey") },
        ) { readStream(it) }
    }

    override suspend fun models(baseUrl: String, apiKey: String): ModelListResult =
        client.listModelIds(joinUrl(baseUrl, "/models")) {
            header(HttpHeaders.Authorization, "Bearer $apiKey")
        }

    private suspend fun FlowCollector<LlmChunk>.readStream(response: HttpResponse) {
        var finished = false
        response.readDataLines { payload ->
            if (payload == "[DONE]") {
                finished = true
                return@readDataLines false
            }
            val chunk = wireJson.decodeFromString(StreamChunk.serializer(), payload)
            if (chunk.error != null) {
                emit(LlmChunk.Failed(LlmError.Server))
                return
            }
            val choice = chunk.choices.firstOrNull()
            choice?.delta?.content?.takeIf { it.isNotEmpty() }?.let { emit(LlmChunk.TextDelta(it)) }
            if (choice?.finishReason != null) finished = true
            chunk.usage?.let { emit(LlmChunk.Usage(it.promptTokens, it.completionTokens)) }
            true
        }
        // A stream that ends without [DONE] or a finish_reason was cut off: never record it as an answer.
        emit(if (finished) LlmChunk.Done else LlmChunk.Failed(LlmError.Network))
    }

    private fun Role.wire(): String = when (this) {
        Role.SYSTEM -> "system"
        Role.USER -> "user"
        Role.ASSISTANT -> "assistant"
        Role.TOOL -> "tool"
    }

    @Serializable
    private class ChatRequest(val model: String, val messages: List<ChatMessage>, val stream: Boolean)

    @Serializable
    private class ChatMessage(val role: String, val content: String)

    @Serializable
    private class StreamChunk(
        val choices: List<Choice> = emptyList(),
        val usage: UsageDto? = null,
        val error: JsonElement? = null,
    )

    @Serializable
    private class Choice(
        val delta: Delta? = null,
        @SerialName("finish_reason") val finishReason: String? = null,
    )

    @Serializable
    private class Delta(val content: String? = null)

    @Serializable
    private class UsageDto(
        @SerialName("prompt_tokens") val promptTokens: Int = 0,
        @SerialName("completion_tokens") val completionTokens: Int = 0,
    )
}
