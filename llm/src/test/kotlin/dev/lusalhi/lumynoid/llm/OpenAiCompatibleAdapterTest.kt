package dev.lusalhi.lumynoid.llm

import dev.lusalhi.lumynoid.core.LlmChunk
import dev.lusalhi.lumynoid.core.LlmError
import dev.lusalhi.lumynoid.core.LlmMessage
import dev.lusalhi.lumynoid.core.LlmRequest
import dev.lusalhi.lumynoid.core.ProviderConfig
import dev.lusalhi.lumynoid.core.ProviderConfigStore
import dev.lusalhi.lumynoid.core.ProviderInput
import dev.lusalhi.lumynoid.core.ProviderSaveResult
import dev.lusalhi.lumynoid.core.Role
import dev.lusalhi.lumynoid.core.SecretPort
import dev.lusalhi.lumynoid.core.SecretRef
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private const val SECRET = "sk-test-SECRET-123"

internal val testConfig = ProviderConfig("p1", "https://example.test/api/v1/", "some/model", SecretRef("k1"))
private val request = LlmRequest(listOf(LlmMessage(Role.USER, "hi")))

private class MemoryStore : ProviderConfigStore, SecretPort {
    var config: ProviderConfig? = null
    val secrets = mutableMapOf<String, String>()
    override suspend fun active() = config
    override suspend fun save(config: ProviderConfig) { this.config = config }
    override suspend fun put(ref: SecretRef, secret: String) { secrets[ref.value] = secret }
    override suspend fun get(ref: SecretRef) = secrets[ref.value]
}

private fun sse(vararg lines: String) = lines.joinToString("\n\n", postfix = "\n\n")

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

        val chunks = adapterFor(engine).stream(testConfig, SECRET, request).toList()

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

        adapterFor(engine).stream(testConfig, SECRET, request).toList()

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
        val chunks = adapterFor(engine).stream(testConfig, SECRET, request).toList()
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
        val chunks = adapterFor(engine).stream(testConfig, SECRET, request).toList()
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
        val chunks = adapterFor(engine).stream(testConfig, SECRET, request).toList()
        assertEquals(listOf(LlmChunk.TextDelta("par"), LlmChunk.Failed(LlmError.Server)), chunks)
    }

    @Test
    fun unauthorizedMapsToAuthFailed() = runTest {
        val engine = MockEngine { respond("{}", HttpStatusCode.Unauthorized) }
        val chunks = adapterFor(engine).stream(testConfig, SECRET, request).toList()
        assertEquals(listOf<LlmChunk>(LlmChunk.Failed(LlmError.AuthFailed)), chunks)
    }

    @Test
    fun rateLimitCarriesRetryAfter() = runTest {
        val engine = MockEngine {
            respond("{}", HttpStatusCode.TooManyRequests, headersOf(HttpHeaders.RetryAfter, "7"))
        }
        val chunks = adapterFor(engine).stream(testConfig, SECRET, request).toList()
        assertEquals(listOf<LlmChunk>(LlmChunk.Failed(LlmError.RateLimited(7000))), chunks)
    }

    @Test
    fun serverErrorMapsToServer() = runTest {
        val engine = MockEngine { respond("oops", HttpStatusCode.BadGateway) }
        val chunks = adapterFor(engine).stream(testConfig, SECRET, request).toList()
        assertEquals(listOf<LlmChunk>(LlmChunk.Failed(LlmError.Server)), chunks)
    }

    @Test
    fun connectionFailureMapsToNetwork() = runTest {
        val engine = MockEngine { throw IOException("unreachable") }
        val chunks = adapterFor(engine).stream(testConfig, SECRET, request).toList()
        assertEquals(listOf<LlmChunk>(LlmChunk.Failed(LlmError.Network)), chunks)
    }

    @Test
    fun routerAnnouncesProviderThenPassesThrough() = runTest {
        val store = MemoryStore().apply { config = testConfig; secrets["k1"] = SECRET }
        val engine = MockEngine { respond(sse("""data: {"choices":[{"delta":{"content":"ok"}}]}""", "data: [DONE]")) }
        val router = LlmRouter(adapterFor(engine), store, store)

        val chunks = router.stream(request).toList()

        assertEquals(
            listOf(LlmChunk.Routed("p1", "some/model"), LlmChunk.TextDelta("ok"), LlmChunk.Done),
            chunks,
        )
    }

    @Test
    fun routerWithoutConfigFailsWithoutCallingTheNetwork() = runTest {
        val store = MemoryStore()
        val engine = MockEngine { respond("never") }
        val chunks = LlmRouter(adapterFor(engine), store, store).stream(request).toList()

        assertEquals(listOf<LlmChunk>(LlmChunk.Failed(LlmError.AuthFailed)), chunks)
        assertTrue(engine.requestHistory.isEmpty())
    }

    @Test
    fun providerAdminKeepsTheKeyOutOfTheConfig() = runTest {
        val store = MemoryStore()
        val admin = ProviderAdmin(store, store)

        admin.save(ProviderInput("  https://example.test/v1 ", SECRET, " m "))
        val saved = store.config!!

        assertEquals("https://example.test/v1", saved.baseUrl)
        assertEquals("m", saved.model)
        assertFalse(saved.toString().contains(SECRET))
        assertEquals(SECRET, store.secrets[saved.apiKey.value])

        admin.save(ProviderInput("https://other.test/v1", "", "m2")) // blank key keeps the old one
        assertEquals(SECRET, store.secrets[store.config!!.apiKey.value])
        assertEquals("", admin.current()!!.apiKey)
    }

    @Test
    fun providerAdminAddsHttpsWhenThePastedUrlHasNoScheme() = runTest {
        val store = MemoryStore()
        val admin = ProviderAdmin(store, store)

        admin.save(ProviderInput("openrouter.ai/api/v1", SECRET, "m"))
        assertEquals("https://openrouter.ai/api/v1", store.config!!.baseUrl)

        admin.save(ProviderInput("http://localhost:11434/v1", "", "m"))
        assertEquals("http://localhost:11434/v1", store.config!!.baseUrl)
    }

    @Test
    fun providerAdminTrimsWhitespaceAroundAPastedKey() = runTest {
        val store = MemoryStore()
        ProviderAdmin(store, store).save(ProviderInput("https://x.test/v1", "  $SECRET\n", "m"))
        assertEquals(SECRET, store.secrets[store.config!!.apiKey.value])
    }

    @Test
    fun providerAdminRejectsIncompleteInputAndStoresNothing() = runTest {
        val store = MemoryStore()
        val admin = ProviderAdmin(store, store)

        assertEquals(ProviderSaveResult.INCOMPLETE, admin.save(ProviderInput("", SECRET, "m")))
        assertEquals(ProviderSaveResult.INCOMPLETE, admin.save(ProviderInput("https://x.test", SECRET, " ")))
        assertEquals(ProviderSaveResult.INCOMPLETE, admin.save(ProviderInput("https://x.test", " ", "m")))

        assertEquals(null, store.config)
        assertTrue(store.secrets.isEmpty())
        assertEquals(null, admin.current())
    }
}
