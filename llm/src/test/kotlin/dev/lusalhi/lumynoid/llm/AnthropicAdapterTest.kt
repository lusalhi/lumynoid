package dev.lusalhi.lumynoid.llm

import dev.lusalhi.lumynoid.core.LlmChunk
import dev.lusalhi.lumynoid.core.LlmError
import dev.lusalhi.lumynoid.core.LlmMessage
import dev.lusalhi.lumynoid.core.LlmRequest
import dev.lusalhi.lumynoid.core.ModelListResult
import dev.lusalhi.lumynoid.core.Role
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
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
        val turns = body.getValue("messages").jsonArray.map { it.jsonObject.getValue("role").jsonPrimitive.content }
        assertEquals(listOf("user", "assistant", "user"), turns)
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
