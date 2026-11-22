package dev.lusalhi.lumynoid.llm

import dev.lusalhi.lumynoid.core.FailureReason
import dev.lusalhi.lumynoid.core.LlmChunk
import dev.lusalhi.lumynoid.core.LlmError
import dev.lusalhi.lumynoid.core.LlmPort
import dev.lusalhi.lumynoid.core.TaskEvent
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

private fun chatFinish(reason: String) = """data: {"choices":[{"delta":{},"finish_reason":"$reason"}]}"""

private fun toolFrame(index: Int, name: String, arguments: String) =
    """data: {"choices":[{"delta":{"tool_calls":[{"index":$index,"function":{"name":"$name","arguments":${JsonPrimitive(arguments)}}}]}}]}"""

private val firstCall = toolFrame(0, "web_lookup", """{"key":"one"}""")

private val partialCall = """data: {"choices":[{"delta":{"tool_calls":[{"index":1,"function":{"name":"web_read"}}]}}]}"""

class OpenAiCompatibleAdapterTest {

    private fun adapterFor(engine: MockEngine) = OpenAiCompatibleAdapter(HttpClient(engine))

    private suspend fun readFrames(vararg frames: String) =
        adapterFor(MockEngine { respond(sse(*frames)) }).stream(TEST_URL, TEST_MODEL, SECRET, request).toList()

    @Test
    fun streamsTextDeltasThenDone() = runTest {
        val engine = MockEngine {
            respond(
                content = sse(
                    ": OPENROUTER PROCESSING",
                    """data: {"choices":[{"delta":{"content":"Hel"}}]}""",
                    """data: {"choices":[{"delta":{"content":"lo"}}]}""",
                    """data: {"choices":[],"usage":{"prompt_tokens":3,"completion_tokens":2}}""",
                    "data: [DONE]",
                ),
                headers = headersOf(HttpHeaders.ContentType, "text/event-stream"),
            )
        }

        val chunks = adapterFor(engine).stream(TEST_URL, TEST_MODEL, SECRET, request).toList()

        assertEquals(
            listOf(
                LlmChunk.TextDelta("Hel"),
                LlmChunk.TextDelta("lo"),
                LlmChunk.Usage(3, 2),
                LlmChunk.Done,
            ),
            chunks,
        )
        assertEquals("https://example.test/api/v1/chat/completions", engine.requestHistory.single().url.toString())
        assertEquals(
            wireJson.parseToJsonElement("""{"model":"some/model","messages":[{"role":"user","content":"hi"}],"stream":true}"""),
            engine.requestJson(0),
        )
    }

    @Test
    fun twoFragmentedCallsRoundTripAndKeepTheirIdsInLaterHistory() = runTest {
        val responses = ArrayDeque(
            listOf(
                sse(
                    """data: {"choices":[{"delta":{"content":"Checking"}}]}""",
                    """data: {"choices":[{"delta":{"tool_calls":[{"index":1,"id":"vendor-b","type":"function","function":{"name":"web_","arguments":"{\"limit\":"}},{"index":0,"id":"vendor-a","type":"function","function":{"name":"web_lookup","arguments":"{\"k"}}]}}]}""",
                    """data: {"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"ey\":\"one\"}"}},{"index":1,"function":{"name":"read","arguments":"2}"}}]}}]}""",
                    chatFinish("tool_calls"),
                ),
                okStream,
                okStream,
            ),
        )
        val engine = MockEngine { respond(responses.removeFirst()) }
        val adapter = adapterFor(engine)
        val fixture = ToolRunnerFixture(backgroundScope, LlmPort { adapter.stream(TEST_URL, TEST_MODEL, SECRET, it) })

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
            assertEquals(JsonPrimitive("function"), definition["type"])
            val spec = definition.getValue("function").jsonObject
            assertEquals(JsonPrimitive(tool.name), spec["name"])
            assertEquals(JsonPrimitive(tool.description), spec["description"])
            assertEquals(tool.inputSchema, spec["parameters"])
        }

        val turns = continuation.getValue("messages").jsonArray.map { it.jsonObject }
        assertEquals(turns.take(1), initial.getValue("messages").jsonArray.map { it.jsonObject })
        assertEquals(listOf("user", "assistant", "tool", "tool"), turns.map { it.getValue("role").jsonPrimitive.content })
        assertEquals(JsonPrimitive("Checking"), turns[1]["content"])
        val calls = turns[1].getValue("tool_calls").jsonArray.map { it.jsonObject }
        val ids = calls.map { it.getValue("id").jsonPrimitive.content }
        assertEquals(2, ids.toSet().size)
        assertTrue(ids.all { it.matches(Regex("call_[A-Za-z0-9_-]+")) })
        assertTrue(ids.none { it in localCalls.map { call -> call.id } || it == "vendor-a" || it == "vendor-b" })
        assertEquals(listOf(JsonPrimitive("function"), JsonPrimitive("function")), calls.map { it["type"] })
        val functions = calls.map { it.getValue("function").jsonObject }
        assertEquals(listOf(JsonPrimitive("web_lookup"), JsonPrimitive("web_read")), functions.map { it["name"] })
        assertEquals(listOf(JsonPrimitive("""{"key":"one"}"""), JsonPrimitive("""{"limit":2}""")), functions.map { it["arguments"] })
        assertEquals(ids.map(::JsonPrimitive), turns.drop(2).map { it["tool_call_id"] })
        assertEquals(listOf(JsonPrimitive("fixture:one"), JsonPrimitive("")), turns.drop(2).map { it["content"] })
        assertEquals(turns, history.take(turns.size))
        assertEquals(JsonPrimitive("ok"), history[4].jsonObject["content"])
        assertEquals(JsonPrimitive("Ask again"), history[5].jsonObject["content"])
    }

    @Test
    fun toolRoundsAcceptADoneMarkerAndRequireCompleteCalls() = runTest {
        suspend fun terminal(vararg frames: String) = readFrames(*frames).last()

        assertEquals(LlmChunk.Done, terminal(firstCall, "data: [DONE]"))
        assertEquals(LlmChunk.Failed(LlmError.Network), terminal(firstCall, partialCall))
        assertEquals(LlmChunk.Failed(LlmError.Server), terminal(firstCall, partialCall, """data: {"error":{"code":500}}"""))
        assertEquals(LlmChunk.Failed(LlmError.MalformedToolCall), terminal(firstCall, chatFinish("length"), "data: [DONE]"))
        assertEquals(LlmChunk.Failed(LlmError.MalformedToolCall), terminal(firstCall, partialCall, "data: [DONE]"))
        assertEquals(LlmChunk.Failed(LlmError.MalformedToolCall), terminal(chatFinish("tool_calls")))
    }

    @Test
    fun aCompletedToolRoundRequiresEverySlotStartingAtZero() = runTest {
        val secondCall = toolFrame(1, "web_read", """{"limit":2}""")
        assertEquals(LlmChunk.Failed(LlmError.MalformedToolCall), readFrames(secondCall, chatFinish("tool_calls")).last())
        assertEquals(LlmChunk.Done, readFrames(secondCall, firstCall, chatFinish("tool_calls")).last())
    }

    @Test
    fun rejectsToolFragmentsAfterFinishAndRetainsUsageFrames() = runTest {
        val finish = chatFinish("stop")
        val usage = """data: {"choices":[],"usage":{"prompt_tokens":3,"completion_tokens":2}}"""

        assertEquals(listOf(LlmChunk.Usage(3, 2), LlmChunk.Done), readFrames(finish, usage, "data: [DONE]"))
        assertEquals(
            listOf(LlmChunk.Usage(3, 2), LlmChunk.Failed(LlmError.MalformedToolCall)),
            readFrames(finish, firstCall, usage, "data: [DONE]"),
        )
    }

    @Test
    fun anErrorFinishReasonFailsWithServer() = runTest {
        assertEquals(
            listOf(LlmChunk.ToolCallDelta(0, "web_lookup", """{"key":"one"}"""), LlmChunk.Failed(LlmError.Server)),
            readFrames(firstCall, chatFinish("error"), "data: [DONE]"),
        )
    }

    @Test
    fun toolArgumentsMustBeStrictJsonObjects() = runTest {
        for (arguments in listOf("""{"limit":""", """{"limit":bogus}""", "[]")) {
            val chunks = readFrames(firstCall, toolFrame(1, "web_read", arguments), chatFinish("tool_calls"))
            assertEquals(LlmChunk.Failed(LlmError.MalformedToolCall), chunks.last(), arguments)
        }
    }

    @Test
    fun missingAndNullArgumentsCanPrecedeTheArgumentFragment() = runTest {
        val nullArguments = """data: {"choices":[{"delta":{"tool_calls":[{"index":1,"function":{"name":"web_read","arguments":null}}]}}]}"""
        val arguments = """data: {"choices":[{"delta":{"tool_calls":[{"index":1,"function":{"arguments":"{}"}}]}}]}"""

        for (nameOnly in listOf(partialCall, nullArguments)) {
            val chunks = readFrames(firstCall, nameOnly, arguments, chatFinish("tool_calls"))
            assertEquals(listOf(LlmChunk.ToolCallDelta(1, "web_read"), LlmChunk.ToolCallDelta(1, argumentsFragment = "{}"), LlmChunk.Done), chunks.drop(1))
        }
    }

    @Test
    fun malformedArgumentsPreventDispatchOfTheWholeRound() = runTest {
        val malformed = """data: {"choices":[{"delta":{"tool_calls":[{"index":1,"function":{"name":"web_read","arguments":{"limit":2}}}]}}]}"""
        val engine = MockEngine { respond(sse(firstCall, malformed, chatFinish("tool_calls"))) }
        val adapter = adapterFor(engine)
        val log = ToolRunnerFixture(backgroundScope, LlmPort { adapter.stream(TEST_URL, TEST_MODEL, SECRET, it) })
            .run("Find fixtures")

        assertEquals(FailureReason.MALFORMED_TOOL_CALL, assertIs<TaskEvent.TaskFailed>(log.last()).reason)
        assertEquals(listOf(1, 2), log.filterIsInstance<TaskEvent.ToolCallRejected>().map { it.callNumber })
        assertEquals(2, log.filterIsInstance<TaskEvent.LlmRetry>().single().callNumber)
        assertTrue(log.none { it is TaskEvent.ToolCallRequested || it is TaskEvent.ToolResult })
        assertEquals(2, engine.requestHistory.size)
        val original = engine.requestJson(0).getValue("messages").jsonArray
        val retry = engine.requestJson(1).getValue("messages").jsonArray
        assertEquals("system", retry.first().jsonObject.getValue("role").jsonPrimitive.content)
        assertEquals(original.toList(), retry.drop(1))
    }

    @Test
    fun apiKeyAppearsOnlyInTheAuthorizationHeader() = runTest {
        val engine = MockEngine { respond(sse("data: [DONE]")) }

        adapterFor(engine).stream(TEST_URL, TEST_MODEL, SECRET, request).toList()

        val sent = engine.requestHistory.single()
        assertEquals("Bearer $SECRET", sent.headers[HttpHeaders.Authorization])
        assertFalse(sent.url.toString().contains(SECRET))
        val body = sent.body.toByteArray().decodeToString()
        assertFalse(body.contains(SECRET))
        assertTrue(body.contains("\"stream\":true"))
        assertTrue(body.contains("\"model\":\"some/model\""))
    }

    @Test
    fun aStreamCutOffBeforeDoneIsAFailureNotAnAnswer() = runTest {
        val engine = MockEngine {
            respond(sse("""data: {"choices":[{"delta":{"content":"par"}}]}"""))
        }
        val chunks = adapterFor(engine).stream(TEST_URL, TEST_MODEL, SECRET, request).toList()
        assertEquals(listOf(LlmChunk.TextDelta("par"), LlmChunk.Failed(LlmError.Network)), chunks)
    }

    @Test
    fun finishReasonCompletesAStreamThatSendsNoDoneMarker() = runTest {
        val engine = MockEngine {
            respond(
                sse(
                    """data: {"choices":[{"delta":{"content":"ok"}}]}""",
                    """data: {"choices":[{"delta":{},"finish_reason":"stop"}]}""",
                ),
            )
        }
        val chunks = adapterFor(engine).stream(TEST_URL, TEST_MODEL, SECRET, request).toList()
        assertEquals(listOf(LlmChunk.TextDelta("ok"), LlmChunk.Done), chunks)
    }

    @Test
    fun anErrorFrameAfterPartialTextFailsWithoutDone() = runTest {
        val engine = MockEngine {
            respond(
                sse(
                    """data: {"choices":[{"delta":{"content":"par"}}]}""",
                    """data: {"error":{"code":500,"message":"boom"}}""",
                ),
            )
        }
        val chunks = adapterFor(engine).stream(TEST_URL, TEST_MODEL, SECRET, request).toList()
        assertEquals(listOf(LlmChunk.TextDelta("par"), LlmChunk.Failed(LlmError.Server)), chunks)
    }

    @Test
    fun aMalformedStreamPayloadFailsWithServerAfterTheTextSoFar() = runTest {
        val engine = MockEngine {
            respond(sse("""data: {"choices":[{"delta":{"content":"par"}}]}""", "data: <html>not json</html>"))
        }
        val chunks = adapterFor(engine).stream(TEST_URL, TEST_MODEL, SECRET, request).toList()
        assertEquals(listOf(LlmChunk.TextDelta("par"), LlmChunk.Failed(LlmError.Server)), chunks)
    }

    @Test
    fun aCollectorThatThrowsSeesItsOwnExceptionNotAFailedChunk() = runTest {
        val engine = MockEngine {
            respond(sse("""data: {"choices":[{"delta":{"content":"par"}}]}""", "data: [DONE]"))
        }
        val seen = mutableListOf<LlmChunk>()
        val thrown = assertFailsWith<IllegalStateException> {
            adapterFor(engine).stream(TEST_URL, TEST_MODEL, SECRET, request).collect {
                seen += it
                throw IllegalStateException("collector boom")
            }
        }
        assertEquals("collector boom", thrown.message)
        assertEquals(listOf<LlmChunk>(LlmChunk.TextDelta("par")), seen)
    }

    @Test
    fun unauthorizedMapsToAuthFailed() = runTest {
        val engine = MockEngine { respond("{}", HttpStatusCode.Unauthorized) }
        val chunks = adapterFor(engine).stream(TEST_URL, TEST_MODEL, SECRET, request).toList()
        assertEquals(listOf<LlmChunk>(LlmChunk.Failed(LlmError.AuthFailed)), chunks)
    }

    @Test
    fun rateLimitCarriesRetryAfter() = runTest {
        val engine = MockEngine {
            respond("{}", HttpStatusCode.TooManyRequests, headersOf(HttpHeaders.RetryAfter, "7"))
        }
        val chunks = adapterFor(engine).stream(TEST_URL, TEST_MODEL, SECRET, request).toList()
        assertEquals(listOf<LlmChunk>(LlmChunk.Failed(LlmError.RateLimited(7000))), chunks)
    }

    @Test
    fun serverErrorMapsToServer() = runTest {
        val engine = MockEngine { respond("oops", HttpStatusCode.BadGateway) }
        val chunks = adapterFor(engine).stream(TEST_URL, TEST_MODEL, SECRET, request).toList()
        assertEquals(listOf<LlmChunk>(LlmChunk.Failed(LlmError.Server)), chunks)
    }

    @Test
    fun connectionFailureMapsToNetwork() = runTest {
        val engine = MockEngine { throw IOException("unreachable") }
        val chunks = adapterFor(engine).stream(TEST_URL, TEST_MODEL, SECRET, request).toList()
        assertEquals(listOf<LlmChunk>(LlmChunk.Failed(LlmError.Network)), chunks)
    }
}
