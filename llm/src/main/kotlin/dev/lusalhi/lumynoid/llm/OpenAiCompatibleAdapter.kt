package dev.lusalhi.lumynoid.llm

import dev.lusalhi.lumynoid.core.LlmChunk
import dev.lusalhi.lumynoid.core.LlmError
import dev.lusalhi.lumynoid.core.LlmRequest
import dev.lusalhi.lumynoid.core.ProviderConfig
import dev.lusalhi.lumynoid.core.Role
import io.ktor.client.HttpClient
import io.ktor.client.request.accept
import io.ktor.client.request.header
import io.ktor.client.request.preparePost
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.utils.io.readLine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import java.io.IOException

/**
 * Streams chat completions from any OpenAI Chat Completions-compatible endpoint (AD-11), written
 * directly on Ktor with no vendor SDK. The API key travels only in the `Authorization` header.
 */
class OpenAiCompatibleAdapter(private val client: HttpClient) {

    fun stream(config: ProviderConfig, apiKey: String, request: LlmRequest): Flow<LlmChunk> = flow {
        val url = config.baseUrl.trimEnd('/') + "/chat/completions"
        val body = ChatRequest(
            model = config.model,
            messages = request.messages.map { ChatMessage(it.role.wire(), it.content) },
            stream = true,
        )
        try {
            client.preparePost(url) {
                header(HttpHeaders.Authorization, "Bearer $apiKey")
                accept(ContentType.Text.EventStream)
                contentType(ContentType.Application.Json)
                setBody(json.encodeToString(ChatRequest.serializer(), body))
            }.execute { response ->
                if (!response.status.isSuccess()) {
                    emit(LlmChunk.Failed(response.toError()))
                } else {
                    readStream(response)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            emit(LlmChunk.Failed(LlmError.Network))
        } catch (e: Exception) {
            emit(LlmChunk.Failed(LlmError.Server))
        }
    }

    private suspend fun FlowCollector<LlmChunk>.readStream(response: HttpResponse) {
        val channel = response.bodyAsChannel()
        var finished = false
        while (true) {
            val line = channel.readLine() ?: break
            if (!line.startsWith("data:")) continue // blank separators and ": keep-alive" comments
            val payload = line.removePrefix("data:").trim()
            if (payload == "[DONE]") {
                finished = true
                break
            }
            val chunk = json.decodeFromString(StreamChunk.serializer(), payload)
            if (chunk.error != null) {
                emit(LlmChunk.Failed(LlmError.Server))
                return
            }
            val choice = chunk.choices.firstOrNull()
            choice?.delta?.content?.takeIf { it.isNotEmpty() }?.let { emit(LlmChunk.TextDelta(it)) }
            if (choice?.finishReason != null) finished = true
            chunk.usage?.let { emit(LlmChunk.Usage(it.promptTokens, it.completionTokens)) }
        }
        // A stream that ends without [DONE] or a finish_reason was cut off: never record it as an answer.
        emit(if (finished) LlmChunk.Done else LlmChunk.Failed(LlmError.Network))
    }

    private fun HttpResponse.toError(): LlmError = when (status) {
        HttpStatusCode.Unauthorized, HttpStatusCode.Forbidden -> LlmError.AuthFailed
        HttpStatusCode.PaymentRequired -> LlmError.QuotaExhausted
        HttpStatusCode.TooManyRequests ->
            LlmError.RateLimited(headers[HttpHeaders.RetryAfter]?.toLongOrNull()?.times(1000))
        else -> LlmError.Server
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

    private companion object {
        val json = Json { ignoreUnknownKeys = true }
    }
}
