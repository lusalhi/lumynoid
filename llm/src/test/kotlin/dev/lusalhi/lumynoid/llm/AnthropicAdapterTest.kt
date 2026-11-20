package dev.lusalhi.lumynoid.llm

import dev.lusalhi.lumynoid.core.LlmChunk
import dev.lusalhi.lumynoid.core.LlmError
import dev.lusalhi.lumynoid.core.LlmMessage
import dev.lusalhi.lumynoid.core.LlmPort
import dev.lusalhi.lumynoid.core.LlmRequest
import dev.lusalhi.lumynoid.core.ModelListResult
import dev.lusalhi.lumynoid.core.Role
import dev.lusalhi.lumynoid.core.TaskEvent
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val KEY = "sk-ant-SECRET-123"
private const val BASE = "https://api.anthropic.com/v1/"
private const val MODEL = "claude-test"

private fun events(vararg datas: String) = datas.joinToString("\n\n", postfix = "\n\n") { data ->
    val type = Regex("\"type\":\"([a-z_]+)\"").find(data)!!.groupValues[1]
    "event: $type\ndata: $data"
}

private fun delta(text: String) = """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"$text"}}"""

private val start = """{"type":"message_start","message":{"id":"m","usage":{"input_tokens":12,"output_tokens":1}}}"""
private val ping = """{"type":"ping"}"""
private val messageDelta = """{"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":5}}"""
private val stop = """{"type":"message_stop"}"""

private fun toolStart(index: Int, name: String, input: String = "{}") =
    """{"type":"content_block_start","index":$index,"content_block":{"type":"tool_use","id":"vendor-$index","name":"$name","input":$input}}"""

private fun toolDelta(index: Int, fragment: String) =
    """{"type":"content_block_delta","index":$index,"delta":{"type":"input_json_delta","partial_json":${JsonPrimitive(fragment)}}}"""

private fun blockStop(index: Int) = """{"type":"content_block_stop","index":$index}"""

private fun stopReason(reason: String) = """{"type":"message_delta","delta":{"stop_reason":"$reason"},"usage":{"output_tokens":5}}"""

class AnthropicAdapterTest {

    private fun adapter(engine: MockEngine) = AnthropicAdapter(HttpClient(engine))

    private val request = LlmRequest(listOf(LlmMessage(Role.USER, "hi")))

    private suspend fun run(engine: MockEngine, request: LlmRequest = this.request) =
        adapter(engine).stream(BASE, MODEL, KEY, request).toList()

    @Test
    fun streamsTextThenUsageThenDone() = runTest {
        val engine = MockEngine {
            respond(
                events(start, ping, delta("Hel"), delta("lo"), messageDelta, stop),
                headers = headersOf(HttpHeaders.ContentType, "text/event-stream"),
            )
        }

        val chunks = run(engine)

        assertEquals(
            listOf(LlmChunk.TextDelta("Hel"), LlmChunk.TextDelta("lo"), LlmChunk.Usage(12, 5), LlmChunk.Done),
            chunks,
        )
    }

    @Test
    fun sendsTheKeyOnlyInTheHeaderAndSystemTextBesideTheTurns() = runTest {
        val engine = MockEngine { respond(events(start, stop)) }
        val request = LlmRequest(
            listOf(
                LlmMessage(Role.SYSTEM, "Be brief."),
                LlmMessage(Role.USER, "hi"),
                LlmMessage(Role.ASSISTANT, "hello"),
                LlmMessage(Role.TOOL, "ignored"),
                LlmMessage(Role.USER, "again"),
            ),
        )

        run(engine, request)

        val sent = engine.requestHistory.single()
        assertEquals("https://api.anthropic.com/v1/messages", sent.url.toString())
        assertEquals(KEY, sent.headers["x-api-key"])
        assertEquals("2023-06-01", sent.headers["anthropic-version"])
        assertNull(sent.headers[HttpHeaders.Authorization])
        assertFalse(sent.url.toString().contains(KEY))
        val text = sent.body.toByteArray().decodeToString()
        assertFalse(text.contains(KEY))
        val body = Json.parseToJsonElement(text).jsonObject
        assertEquals(MODEL, body.getValue("model").jsonPrimitive.content)
        assertEquals("8192", body.getValue("max_tokens").jsonPrimitive.content)
        assertEquals("true", body.getValue("stream").jsonPrimitive.content)
        assertEquals("Be brief.", body.getValue("system").jsonPrimitive.content)
        val turns = body.getValue("messages").jsonArray.map { it.jsonObject }
        assertEquals(listOf("user", "assistant", "user"), turns.map { it.getValue("role").jsonPrimitive.content })
        assertEquals(listOf(JsonPrimitive("hi"), JsonPrimitive("hello"), JsonPrimitive("again")), turns.map { it["content"] })
        assertFalse("tools" in body)
    }

    @Test
    fun twoFragmentedCallsRoundTripAndKeepTheirIdsInLaterHistory() = runTest {
        val responses = ArrayDeque(
            listOf(
                events(
                    start,
                    delta("Checking"),
                    blockStop(0),
                    toolStart(1, "web_lookup"),
                    toolDelta(1, """{"key":"""),
                    toolDelta(1, """"one"}"""),
                    blockStop(1),
                    toolStart(2, "web_read"),
                    toolDelta(2, """{"limit":"""),
                    toolDelta(2, "2}"),
                    blockStop(2),
                    stopReason("tool_use"),
                    stop,
                ),
                events(start, delta("ok"), messageDelta, stop),
                events(start, delta("next"), messageDelta, stop),
            ),
        )
        val engine = MockEngine { respond(responses.removeFirst()) }
        val adapter = adapter(engine)
        val fixture = ToolRunnerFixture(backgroundScope, LlmPort { adapter.stream(BASE, MODEL, KEY, it) })

        val log = fixture.run("Find fixtures")
        assertEquals("ok", log.filterIsInstance<TaskEvent.AssistantMessage>().single().text)
        assertIs<TaskEvent.TaskFinished>(log.last())
        val localCalls = log.filterIsInstance<TaskEvent.ToolCallRequested>().single().calls.map { it.call }
        assertEquals(localCalls.map { it.id }, log.filterIsInstance<TaskEvent.ToolResult>().map { it.callId })
        fixture.run("Ask again")

        val initial = engine.requestJson(0)
        val continuation = engine.requestJson(1)
        val history = engine.requestJson(2).getValue("messages").jsonArray
        val definitions = initial.getValue("tools").jsonArray.map { it.jsonObject }
        assertEquals(initial["tools"], continuation["tools"])
        assertEquals(fixture.tools.size, definitions.size)
        for ((tool, definition) in fixture.tools.zip(definitions)) {
            assertEquals(JsonPrimitive(tool.name), definition["name"])
            assertEquals(JsonPrimitive(tool.description), definition["description"])
            assertEquals(tool.inputSchema, definition["input_schema"])
        }

        val turns = continuation.getValue("messages").jsonArray.map { it.jsonObject }
        assertEquals(turns.take(1), initial.getValue("messages").jsonArray.map { it.jsonObject })
        assertEquals(listOf("user", "assistant", "user"), turns.map { it.getValue("role").jsonPrimitive.content })
        val assistantBlocks = turns[1].getValue("content").jsonArray.map { it.jsonObject }
        assertEquals(listOf("text", "tool_use", "tool_use"), assistantBlocks.map { it.getValue("type").jsonPrimitive.content })
        assertEquals(JsonPrimitive("Checking"), assistantBlocks.first()["text"])
        val tools = assistantBlocks.drop(1)
        val ids = tools.map { it.getValue("id").jsonPrimitive.content }
        assertEquals(2, ids.toSet().size)
        assertTrue(ids.all { it.matches(Regex("toolu_[A-Za-z0-9_-]+")) })
        assertTrue(ids.none { it in localCalls.map { call -> call.id } || it == "vendor-1" || it == "vendor-2" })
        assertEquals(listOf(JsonPrimitive("web_lookup"), JsonPrimitive("web_read")), tools.map { it["name"] })
        assertEquals(
            listOf(Json.parseToJsonElement("""{"key":"one"}"""), Json.parseToJsonElement("""{"limit":2}""")),
            tools.map { it["input"] },
        )
        val results = turns[2].getValue("content").jsonArray.map { it.jsonObject }
        assertEquals(listOf("tool_result", "tool_result"), results.map { it.getValue("type").jsonPrimitive.content })
        assertEquals(ids.map(::JsonPrimitive), results.map { it["tool_use_id"] })
        assertEquals(JsonPrimitive("fixture:one"), results[0]["content"])
        assertFalse("content" in results[1])
        assertEquals(turns, history.take(turns.size))
        assertEquals(JsonPrimitive("ok"), history[3].jsonObject["content"])
        assertEquals(JsonPrimitive("Ask again"), history[4].jsonObject["content"])
    }

    @Test
    fun aCompletedEmptyToolBlockEmitsAnEmptyObject() = runTest {
        val chunks = run(MockEngine { respond(events(toolStart(0, "web_lookup"), blockStop(0), stopReason("tool_use"), stop)) })
        assertEquals(
            listOf(
                LlmChunk.ToolCallDelta(0, nameFragment = "web_lookup"),
                LlmChunk.ToolCallDelta(0, argumentsFragment = "{}"),
                LlmChunk.Usage(0, 5),
                LlmChunk.Done,
            ),
            chunks,
        )
    }

    @Test
    fun toolRoundsRequireCompletedBlocksAndAMessageStop() = runTest {
        val tool = toolStart(0, "web_lookup")
        suspend fun terminal(vararg streamEvents: String) =
            run(MockEngine { respond(events(*streamEvents)) }).last()

        assertEquals(LlmChunk.Failed(LlmError.Network), terminal(tool, blockStop(0), stopReason("tool_use")))
        assertEquals(LlmChunk.Failed(LlmError.AuthFailed), terminal(tool, """{"type":"error","error":{"type":"permission_error"}}"""))
        assertEquals(LlmChunk.Failed(LlmError.MalformedToolCall), terminal(tool, stopReason("tool_use"), stop))
        assertEquals(LlmChunk.Failed(LlmError.MalformedToolCall), terminal(tool, blockStop(0), stopReason("max_tokens"), stop))
        assertEquals(LlmChunk.Failed(LlmError.MalformedToolCall), terminal(stopReason("tool_use"), stop))
    }

    @Test
    fun toolArgumentsMustBeStrictJsonObjects() = runTest {
        for (arguments in listOf("""{"limit":""", """{"limit":bogus}""", "[]")) {
            val body = events(
                toolStart(0, "web_lookup", """{"key":"one"}"""),
                blockStop(0),
                toolStart(1, "web_read"),
                toolDelta(1, arguments),
                blockStop(1),
                stopReason("tool_use"),
                stop,
            )
            assertEquals(LlmChunk.Failed(LlmError.MalformedToolCall), run(MockEngine { respond(body) }).last(), arguments)
        }
    }

    @Test
    fun malformedArgumentsPreventDispatchOfTheWholeRound() = runTest {
        val engine = MockEngine {
            respond(
                events(
                    toolStart(0, "web_lookup", """{"key":"one"}"""),
                    blockStop(0),
                    toolStart(1, "web_read"),
                    toolDelta(1, """{"limit":"""),
                    blockStop(1),
                    stopReason("tool_use"),
                    stop,
                ),
            )
        }
        val adapter = adapter(engine)
        val log = ToolRunnerFixture(backgroundScope, LlmPort { adapter.stream(BASE, MODEL, KEY, it) })
            .run("Find fixtures")

        assertIs<TaskEvent.TaskFailed>(log.last())
        assertTrue(log.none { it is TaskEvent.ToolCallRequested || it is TaskEvent.ToolResult })
        assertEquals(1, engine.requestHistory.size)
    }

    @Test
    fun withoutSystemMessagesTheRequestHasNoSystemField() = runTest {
        val engine = MockEngine { respond(events(start, stop)) }
        run(engine)
        val body = Json.parseToJsonElement(engine.requestHistory.single().body.toByteArray().decodeToString()).jsonObject
        assertFalse("system" in body)
    }

    @Test
    fun anEmptyReplyStillEndsInDoneAndTheRunnerDecidesWhatThatMeans() = runTest {
        val chunks = run(MockEngine { respond(events(start, stop)) })
        assertEquals(listOf(LlmChunk.Usage(12, 0), LlmChunk.Done), chunks)
    }

    @Test
    fun aStreamCutOffBeforeMessageStopIsAFailureNotAnAnswer() = runTest {
        val chunks = run(MockEngine { respond(events(start, delta("par"))) })
        assertEquals(listOf(LlmChunk.TextDelta("par"), LlmChunk.Failed(LlmError.Network)), chunks)
    }

    @Test
    fun aMalformedStreamPayloadFailsWithServerAfterTheTextSoFar() = runTest {
        val body = events(start, delta("par")) + "event: content_block_delta\ndata: <html>not json</html>\n\n"
        val chunks = run(MockEngine { respond(body) })
        assertEquals(listOf(LlmChunk.TextDelta("par"), LlmChunk.Failed(LlmError.Server)), chunks)
    }

    @Test
    fun anErrorEventAfterPartialTextFailsWithoutDone() = runTest {
        fun errorEvent(type: String) = """{"type":"error","error":{"type":"$type","message":"details"}}"""

        suspend fun failureFor(type: String) =
            run(MockEngine { respond(events(start, delta("par"), errorEvent(type))) }).last()

        assertEquals(LlmChunk.Failed(LlmError.Server), failureFor("overloaded_error"))
        assertEquals(LlmChunk.Failed(LlmError.Server), failureFor("api_error"))
        assertEquals(LlmChunk.Failed(LlmError.RateLimited(null)), failureFor("rate_limit_error"))
        assertEquals(LlmChunk.Failed(LlmError.AuthFailed), failureFor("authentication_error"))
        assertEquals(LlmChunk.Failed(LlmError.QuotaExhausted), failureFor("billing_error"))
        assertTrue(run(MockEngine { respond(events(start, delta("par"), errorEvent("api_error"))) }).none { it == LlmChunk.Done })
    }

    @Test
    fun httpErrorsMapToTheSameTypedErrorsAsEveryProvider() = runTest {
        suspend fun failureFor(status: HttpStatusCode, headers: io.ktor.http.Headers = headersOf()) =
            run(MockEngine { respond("""{"error":{"message":"$KEY leaked"}}""", status, headers) })

        assertEquals(listOf<LlmChunk>(LlmChunk.Failed(LlmError.AuthFailed)), failureFor(HttpStatusCode.Unauthorized))
        assertEquals(listOf<LlmChunk>(LlmChunk.Failed(LlmError.AuthFailed)), failureFor(HttpStatusCode.Forbidden))
        assertEquals(listOf<LlmChunk>(LlmChunk.Failed(LlmError.QuotaExhausted)), failureFor(HttpStatusCode.PaymentRequired))
        assertEquals(
            listOf<LlmChunk>(LlmChunk.Failed(LlmError.RateLimited(7000))),
            failureFor(HttpStatusCode.TooManyRequests, headersOf(HttpHeaders.RetryAfter, "7")),
        )
        assertEquals(listOf<LlmChunk>(LlmChunk.Failed(LlmError.Server)), failureFor(HttpStatusCode(529, "Overloaded")))
        assertEquals(listOf<LlmChunk>(LlmChunk.Failed(LlmError.Server)), failureFor(HttpStatusCode.InternalServerError))
    }

    @Test
    fun connectionFailureMapsToNetwork() = runTest {
        val chunks = run(MockEngine { throw IOException("unreachable") })
        assertEquals(listOf<LlmChunk>(LlmChunk.Failed(LlmError.Network)), chunks)
    }

    @Test
    fun listsModelIdsWithTheKeyInTheHeaders() = runTest {
        val engine = MockEngine {
            respond("""{"data":[{"id":"claude-a","display_name":"A"},{"id":"claude-b"}],"has_more":false}""")
        }

        val result = adapter(engine).models(BASE, KEY)

        assertEquals(ModelListResult.Models(listOf("claude-a", "claude-b")), result)
        val sent = engine.requestHistory.single()
        assertEquals("https://api.anthropic.com/v1/models?limit=1000", sent.url.toString())
        assertEquals(KEY, sent.headers["x-api-key"])
        assertEquals("2023-06-01", sent.headers["anthropic-version"])
    }

    @Test
    fun modelListFallsBackToManualEntryOnAnythingUnusable() = runTest {
        suspend fun result(engine: MockEngine) = adapter(engine).models(BASE, KEY)

        assertEquals(ModelListResult.Unavailable(LlmError.AuthFailed), result(MockEngine { respond("{}", HttpStatusCode.Unauthorized) }))
        assertEquals(ModelListResult.Unavailable(null), result(MockEngine { respond("""{"data":[]}""") }))
        assertEquals(ModelListResult.Unavailable(LlmError.Server), result(MockEngine { respond("<html>") }))
        assertEquals(ModelListResult.Unavailable(LlmError.Network), result(MockEngine { throw IOException("down") }))
    }
}
