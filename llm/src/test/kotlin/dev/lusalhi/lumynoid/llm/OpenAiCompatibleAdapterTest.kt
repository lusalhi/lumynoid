package dev.lusalhi.lumynoid.llm

import dev.lusalhi.lumynoid.core.LlmChunk
import dev.lusalhi.lumynoid.core.LlmError
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
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OpenAiCompatibleAdapterTest {

    private fun adapterFor(engine: MockEngine) = OpenAiCompatibleAdapter(HttpClient(engine))

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
