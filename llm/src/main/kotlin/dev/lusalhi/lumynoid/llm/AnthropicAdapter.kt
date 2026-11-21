package dev.lusalhi.lumynoid.llm

import dev.lusalhi.lumynoid.core.LlmChunk
import dev.lusalhi.lumynoid.core.LlmError
import dev.lusalhi.lumynoid.core.LlmMessage
import dev.lusalhi.lumynoid.core.LlmRequest
import dev.lusalhi.lumynoid.core.ModelListResult
import dev.lusalhi.lumynoid.core.Role
import dev.lusalhi.lumynoid.core.parseToolArguments
import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Streams answers from the Anthropic Messages API (AD-11), written directly on Ktor with no vendor SDK.
 * Only an API key is used (C10); it travels only in the `x-api-key` header.
 */
class AnthropicAdapter(private val client: HttpClient) : WireAdapter {

    override fun stream(baseUrl: String, model: String, apiKey: String, request: LlmRequest): Flow<LlmChunk> = flow {
        val turns = try {
            request.messages.toTurns()
        } catch (_: IllegalArgumentException) {
            emit(LlmChunk.Failed(LlmError.MalformedToolCall))
            return@flow
        }
        val body = MessagesRequest(
            model = model,
            maxTokens = MAX_TOKENS,
            stream = true,
            // Anthropic takes system text beside the turns.
            system = request.messages.filter { it.role == Role.SYSTEM }.joinToString("\n\n") { it.content }.ifBlank { null },
            messages = turns,
            tools = request.tools.map { tool ->
                ToolDefinition(tool.name, tool.description, tool.inputSchema)
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

    private fun List<LlmMessage>.toTurns(): List<Turn> = buildList {
        val results = mutableListOf<JsonObject>()
        for (message in this@toTurns) {
            if (message.role == Role.SYSTEM) continue
            if (message.role == Role.TOOL) {
                val id = message.toolCallId ?: continue
                results += buildJsonObject {
                    put("type", "tool_result")
                    put("tool_use_id", callId(id))
                    if (message.content.isNotEmpty()) put("content", message.content)
                }
                continue
            }
            if (results.isNotEmpty()) {
                add(Turn("user", JsonArray(results.toList())))
                results.clear()
            }
            add(
                if (message.role == Role.ASSISTANT) {
                    Turn("assistant", message.assistantContent())
                } else {
                    Turn("user", JsonPrimitive(message.content))
                },
            )
        }
        if (results.isNotEmpty()) add(Turn("user", JsonArray(results)))
    }

    private fun LlmMessage.assistantContent(): JsonElement {
        if (toolCalls.isEmpty()) return JsonPrimitive(content)
        return JsonArray(buildList {
            if (content.isNotEmpty()) {
                add(buildJsonObject {
                    put("type", "text")
                    put("text", content)
                })
            }
            for (call in toolCalls) {
                val input = requireNotNull(parseToolArguments(call.argsJson))
                add(buildJsonObject {
                    put("type", "tool_use")
                    put("id", callId(call.id))
                    put("name", call.name)
                    put("input", input)
                })
            }
        })
    }

    private fun callId(localId: String) = toolCallWireId("toolu_", localId)

    private fun HttpRequestBuilder.authenticate(apiKey: String) {
        header("x-api-key", apiKey)
        header("anthropic-version", API_VERSION)
    }

    private suspend fun FlowCollector<LlmChunk>.readStream(response: HttpResponse) {
        var inputTokens = 0
        var outputTokens = 0
        var finished = false
        var stopReason: String? = null
        var malformed = false
        val toolBlocks = mutableMapOf<Int, ToolBlockProgress>()
        response.readDataLines { payload ->
            val event = wireJson.decodeFromString(StreamEvent.serializer(), payload)
            val index = event.index
            when (event.type) {
                "message_start" -> inputTokens = event.message?.usage?.inputTokens ?: inputTokens
                "content_block_start" -> {
                    val block = event.contentBlock
                    if (block?.type == "tool_use") {
                        val input = block.input as? JsonObject
                        if (index == null || index < 0 || index in toolBlocks || block.name.isNullOrBlank() ||
                            (block.input != null && input == null)
                        ) {
                            malformed = true
                        } else {
                            toolBlocks[index] = ToolBlockProgress(input)
                            emit(LlmChunk.ToolCallDelta(index, nameFragment = block.name))
                        }
                    }
                }
                "content_block_delta" -> {
                    val delta = event.delta
                    val block = index?.let(toolBlocks::get)
                    if (delta?.type == "input_json_delta") {
                        val fragment = (delta.partialJson as? JsonPrimitive)?.takeIf { it.isString }?.content
                        if (index == null || block == null || block.stopped || fragment == null ||
                            block.initialInput?.isNotEmpty() == true
                        ) {
                            malformed = true
                        } else {
                            block.appendArguments(fragment)
                            emit(LlmChunk.ToolCallDelta(index, argumentsFragment = fragment))
                        }
                    } else if (block != null) {
                        malformed = true
                    } else {
                        delta?.text?.takeIf { it.isNotEmpty() }?.let { emit(LlmChunk.TextDelta(it)) }
                    }
                }
                "content_block_stop" -> {
                    val block = index?.let(toolBlocks::get)
                    if (index != null && block != null) {
                        if (block.stopped) {
                            malformed = true
                        } else if (block.argumentFragments == null && block.initialInput != null) {
                            emit(LlmChunk.ToolCallDelta(index, argumentsFragment = block.initialInput.toString()))
                        }
                        block.stopped = true
                    }
                }
                "message_delta" -> {
                    outputTokens = event.usage?.outputTokens ?: outputTokens
                    stopReason = event.delta?.stopReason ?: stopReason
                }
                "message_stop" -> {
                    finished = true
                    return@readDataLines false
                }
                "error" -> {
                    emit(LlmChunk.Failed(anthropicError(event.error?.type)))
                    return
                }
            }
            true
        }
        // A stream that ends without message_stop was cut off: never record it as an answer.
        if (!finished) {
            emit(LlmChunk.Failed(LlmError.Network))
            return
        }
        if (malformed ||
            (stopReason == "tool_use" && toolBlocks.isEmpty()) ||
            (toolBlocks.isNotEmpty() && (stopReason == "max_tokens" || stopReason == "model_context_window_exceeded")) ||
            toolBlocks.values.any { !it.stopped || !it.hasValidArguments() }
        ) {
            emit(LlmChunk.Failed(LlmError.MalformedToolCall))
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
        val tools: List<ToolDefinition> = emptyList(),
    )

    @Serializable
    private class Turn(val role: String, val content: JsonElement)

    @Serializable
    private class ToolDefinition(
        val name: String,
        val description: String,
        @SerialName("input_schema") val inputSchema: JsonObject,
    )

    @Serializable
    private class StreamEvent(
        val type: String,
        val message: StartedMessage? = null,
        val index: Int? = null,
        @SerialName("content_block") val contentBlock: ContentBlock? = null,
        val delta: Delta? = null,
        val usage: UsageDto? = null,
        val error: ErrorBody? = null,
    )

    @Serializable
    private class StartedMessage(val usage: UsageDto? = null)

    @Serializable
    private class ContentBlock(
        val type: String? = null,
        val name: String? = null,
        val input: JsonElement? = null,
    )

    @Serializable
    private class Delta(
        val type: String? = null,
        val text: String? = null,
        @SerialName("partial_json") val partialJson: JsonElement? = null,
        @SerialName("stop_reason") val stopReason: String? = null,
    )

    private class ToolBlockProgress(val initialInput: JsonObject?) {
        var argumentFragments: StringBuilder? = null
            private set
        var stopped = false

        fun appendArguments(fragment: String) {
            val fragments = argumentFragments ?: StringBuilder().also { argumentFragments = it }
            fragments.append(fragment)
        }

        fun hasValidArguments(): Boolean {
            val arguments = argumentFragments?.toString() ?: initialInput?.toString() ?: return false
            return parseToolArguments(arguments) != null
        }
    }

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
