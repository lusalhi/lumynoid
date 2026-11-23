package dev.lusalhi.lumynoid.llm

import dev.lusalhi.lumynoid.core.LlmChunk
import dev.lusalhi.lumynoid.core.LlmError
import dev.lusalhi.lumynoid.core.LlmRequest
import dev.lusalhi.lumynoid.core.ModelListResult
import dev.lusalhi.lumynoid.core.Role
import dev.lusalhi.lumynoid.core.parseToolArguments
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
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject

/**
 * Streams chat completions from any OpenAI Chat Completions-compatible endpoint (AD-11), written
 * directly on Ktor with no vendor SDK. The API key travels only in the `Authorization` header.
 */
class OpenAiCompatibleAdapter(private val client: HttpClient) : WireAdapter {

    override fun stream(baseUrl: String, model: String, apiKey: String, request: LlmRequest): Flow<LlmChunk> = flow {
        val body = ChatRequest(
            model = model,
            messages = request.messages.map { message ->
                ChatMessage(
                    role = message.role.wire(),
                    content = message.content,
                    toolCalls = message.toolCalls.map { call ->
                        FunctionCall(callId(call.id), "function", FunctionArguments(call.name, call.argsJson))
                    },
                    toolCallId = message.toolCallId?.let(::callId),
                )
            },
            stream = true,
            tools = request.tools.map { tool ->
                FunctionTool("function", FunctionDefinition(tool.name, tool.description, tool.inputSchema))
            },
        )
        streamPost(
            client,
            joinUrl(baseUrl, "/chat/completions"),
            wireJson.encodeToString(ChatRequest.serializer(), body),
            auth = { header(HttpHeaders.Authorization, "Bearer $apiKey") },
        ) { readStream(it, request.requireCompleteText) }
    }

    override suspend fun models(baseUrl: String, apiKey: String): ModelListResult =
        client.listModelIds(joinUrl(baseUrl, "/models")) {
            header(HttpHeaders.Authorization, "Bearer $apiKey")
        }

    private suspend fun FlowCollector<LlmChunk>.readStream(response: HttpResponse, requireCompleteText: Boolean) {
        var doneMarker = false
        var finishReason: String? = null
        var malformed = false
        val calls = mutableMapOf<Int, CallProgress>()
        response.readDataLines { payload ->
            if (payload == "[DONE]") {
                doneMarker = true
                return@readDataLines false
            }
            val chunk = wireJson.decodeFromString(StreamChunk.serializer(), payload)
            val choice = chunk.choices.firstOrNull()
            if (chunk.error != null || choice?.finishReason == "error") {
                emit(LlmChunk.Failed(LlmError.Server))
                return
            }
            val delta = choice?.delta
            delta?.content?.takeIf { it.isNotEmpty() }?.let { emit(LlmChunk.TextDelta(it)) }
            for (call in delta?.toolCalls.orEmpty()) {
                val index = call.index
                if (finishReason != null || index == null || index < 0 ||
                    (call.type != null && call.type != "function")
                ) {
                    malformed = true
                    continue
                }
                val name = call.function?.name
                val rawArguments = call.function?.arguments
                val arguments = rawArguments.wireStringOrNull()
                if (rawArguments != null && rawArguments != JsonNull && arguments == null) {
                    malformed = true
                    continue
                }
                val progress = calls.getOrPut(index, ::CallProgress)
                if (!name.isNullOrBlank()) progress.hasName = true
                arguments?.let(progress.arguments::append)
                if (name != null || arguments != null) emit(LlmChunk.ToolCallDelta(index, name, arguments))
            }
            if (finishReason == null) finishReason = choice?.finishReason
            chunk.usage?.let { emit(LlmChunk.Usage(it.promptTokens, it.completionTokens)) }
            true
        }
        val error = when {
            !doneMarker && finishReason == null -> LlmError.Network
            malformed || calls.keys.any { it !in 0 until calls.size } -> LlmError.MalformedToolCall
            calls.isEmpty() && (finishReason == "tool_calls" || finishReason == "function_call") ->
                LlmError.MalformedToolCall
            calls.isNotEmpty() && (finishReason == "length" || finishReason == "content_filter") ->
                LlmError.MalformedToolCall
            calls.values.any { !it.hasName || parseToolArguments(it.arguments.toString()) == null } ->
                LlmError.MalformedToolCall
            requireCompleteText && (finishReason == "length" || finishReason == "content_filter") -> LlmError.Server
            else -> null
        }
        emit(if (error == null) LlmChunk.Done else LlmChunk.Failed(error))
    }

    private fun callId(localId: String) = toolCallWireId("call_", localId)

    private fun Role.wire(): String = when (this) {
        Role.SYSTEM -> "system"
        Role.USER -> "user"
        Role.ASSISTANT -> "assistant"
        Role.TOOL -> "tool"
    }

    @Serializable
    private class ChatRequest(
        val model: String,
        val messages: List<ChatMessage>,
        val stream: Boolean,
        val tools: List<FunctionTool> = emptyList(),
    )

    @Serializable
    private class ChatMessage(
        val role: String,
        val content: String,
        @SerialName("tool_calls") val toolCalls: List<FunctionCall> = emptyList(),
        @SerialName("tool_call_id") val toolCallId: String? = null,
    )

    @Serializable
    private class FunctionTool(val type: String, val function: FunctionDefinition)

    @Serializable
    private class FunctionDefinition(val name: String, val description: String, val parameters: JsonObject)

    @Serializable
    private class FunctionCall(val id: String, val type: String, val function: FunctionArguments)

    @Serializable
    private class FunctionArguments(val name: String, val arguments: String)

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
    private class Delta(
        val content: String? = null,
        @SerialName("tool_calls") val toolCalls: List<CallDelta>? = null,
    )

    @Serializable
    private class CallDelta(
        val index: Int? = null,
        val type: String? = null,
        val function: FunctionDelta? = null,
    )

    @Serializable
    private class FunctionDelta(val name: String? = null, val arguments: JsonElement? = null)

    private class CallProgress {
        var hasName = false
        val arguments = StringBuilder()
    }

    @Serializable
    private class UsageDto(
        @SerialName("prompt_tokens") val promptTokens: Int = 0,
        @SerialName("completion_tokens") val completionTokens: Int = 0,
    )
}
