package dev.lusalhi.lumynoid.llm

import dev.lusalhi.lumynoid.core.LlmChunk
import dev.lusalhi.lumynoid.core.LlmError
import dev.lusalhi.lumynoid.core.LlmMessage
import dev.lusalhi.lumynoid.core.LlmRequest
import dev.lusalhi.lumynoid.core.ActiveModel
import dev.lusalhi.lumynoid.core.ConnectionTestResult
import dev.lusalhi.lumynoid.core.IdGenerator
import dev.lusalhi.lumynoid.core.ModelListResult
import dev.lusalhi.lumynoid.core.ModelProfile
import dev.lusalhi.lumynoid.core.ProviderConfig
import dev.lusalhi.lumynoid.core.ProviderConfigStore
import dev.lusalhi.lumynoid.core.ProviderDraft
import dev.lusalhi.lumynoid.core.ProviderKind
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

private const val TEST_URL = "https://example.test/api/v1/"
private const val TEST_MODEL = "some/model"
private val request = LlmRequest(listOf(LlmMessage(Role.USER, "hi")))

private class MemoryStore : ProviderConfigStore, SecretPort {
    val configs = linkedMapOf<String, ProviderConfig>()
    val modelRows = mutableListOf<ModelProfile>()
    var activeModel: ActiveModel? = null
    val secrets = mutableMapOf<String, String>()
    override suspend fun providers() = configs.values.toList()
    override suspend fun provider(id: String) = configs[id]
    override suspend fun save(config: ProviderConfig) { configs[config.id] = config }
    override suspend fun models(providerId: String) = modelRows.filter { it.providerId == providerId }
    override suspend fun saveModel(model: ModelProfile) {
        modelRows.removeAll { it.providerId == model.providerId && it.modelId == model.modelId }
        modelRows += model
    }
    override suspend fun active() = activeModel
    override suspend fun setActive(active: ActiveModel) { activeModel = active }
    override suspend fun put(ref: SecretRef, secret: String) { secrets[ref.value] = secret }
    override suspend fun get(ref: SecretRef) = secrets[ref.value]
}

private class CountingIds : IdGenerator {
    private var n = 0
    override fun next() = "p${++n}"
}

private fun draft(
    url: String = "https://x.test/v1",
    key: String = SECRET,
    model: String = "m",
    id: String? = null,
    makeActive: Boolean = false,
    presetId: String? = null,
    name: String = "",
    kind: ProviderKind = ProviderKind.OPENAI_COMPATIBLE,
) = ProviderDraft(id, presetId, name, url, key, model, makeActive, kind)

private fun sse(vararg lines: String) = lines.joinToString("\n\n", postfix = "\n\n")

class OpenAiCompatibleAdapterTest {


    private fun adapterFor(engine: MockEngine) = OpenAiCompatibleAdapter(HttpClient(engine))

    private fun adaptersFor(engine: MockEngine) = ProviderAdapters(HttpClient(engine))

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

    private fun admin(store: MemoryStore, engine: MockEngine = MockEngine { respond("never") }) =
        ProviderAdmin(adaptersFor(engine), store, store, CountingIds())

    private val okStream = sse("""data: {"choices":[{"delta":{"content":"ok"}}]}""", "data: [DONE]")

    @Test
    fun routerAnnouncesTheActiveProviderAndModelThenPassesThrough() = runTest {
        val store = MemoryStore()
        val engine = MockEngine { respond(okStream) }
        admin(store).save(draft(url = "https://example.test/v1", model = "chosen/model"))
        val router = LlmRouter(adaptersFor(engine), store, store)

        val chunks = router.stream(request).toList()

        assertEquals(
            listOf(LlmChunk.Routed("p1", "chosen/model"), LlmChunk.TextDelta("ok"), LlmChunk.Done),
            chunks,
        )
        assertTrue(engine.requestHistory.single().body.toByteArray().decodeToString().contains("\"model\":\"chosen/model\""))
    }

    @Test
    fun routerWithoutAnActiveProviderFailsWithoutCallingTheNetwork() = runTest {
        val store = MemoryStore()
        val engine = MockEngine { respond("never") }
        val chunks = LlmRouter(adaptersFor(engine), store, store).stream(request).toList()

        assertEquals(listOf<LlmChunk>(LlmChunk.Failed(LlmError.AuthFailed)), chunks)
        assertTrue(engine.requestHistory.isEmpty())
    }

    @Test
    fun switchingTheActiveModelChangesTheVeryNextStream() = runTest {
        val store = MemoryStore()
        val engine = MockEngine { respond(okStream) }
        val admin = admin(store)
        admin.save(draft(url = "https://one.test/v1", model = "m1")) // first provider: active
        admin.save(draft(url = "https://two.test/v1", model = "m2", key = "sk-two"))
        val router = LlmRouter(adaptersFor(engine), store, store)

        router.stream(request).toList()
        admin.select("p2", "m2")
        router.stream(request).toList()

        val urls = engine.requestHistory.map { it.url.toString() }
        assertEquals(listOf("https://one.test/v1/chat/completions", "https://two.test/v1/chat/completions"), urls)
        assertEquals("Bearer sk-two", engine.requestHistory[1].headers[HttpHeaders.Authorization])
    }

    @Test
    fun anAnswerAlreadyStreamingFinishesOnTheProviderItStartedWith() = runTest {
        val store = MemoryStore()
        val engine = MockEngine { respond(okStream) }
        val admin = admin(store)
        admin.save(draft(url = "https://one.test/v1", model = "m1"))
        admin.save(draft(url = "https://two.test/v1", model = "m2", key = "sk-two"))
        val router = LlmRouter(adaptersFor(engine), store, store)

        val seen = mutableListOf<LlmChunk>()
        router.stream(request).collect {
            seen += it
            if (it is LlmChunk.Routed) admin.select("p2", "m2") // the user switches while this answer streams
        }

        assertEquals(listOf(LlmChunk.Routed("p1", "m1"), LlmChunk.TextDelta("ok"), LlmChunk.Done), seen)
        assertEquals(listOf("https://one.test/v1/chat/completions"), engine.requestHistory.map { it.url.toString() })
        assertEquals(ActiveModel("p2", "m2"), store.active())
    }

    @Test
    fun theFirstSavedProviderBecomesActiveAndLaterOnesDoNotTakeOver() = runTest {
        val store = MemoryStore()
        val admin = admin(store)

        admin.save(draft(url = "https://one.test/v1", model = "m1"))
        admin.save(draft(url = "https://two.test/v1", model = "m2"))
        assertEquals(ActiveModel("p1", "m1"), store.active())

        admin.save(draft(url = "https://two.test/v1", model = "m2", id = "p2", key = "", makeActive = true))
        assertEquals(ActiveModel("p2", "m2"), store.active())

        // Editing the active provider's model moves the active model with it.
        admin.save(draft(url = "https://two.test/v1", model = "m3", id = "p2", key = ""))
        assertEquals(ActiveModel("p2", "m3"), store.active())
        assertEquals(listOf("m2", "m3"), admin.overview().providers.single { it.id == "p2" }.models)
    }

    @Test
    fun providerAdminKeepsTheKeyOutOfTheConfig() = runTest {
        val store = MemoryStore()
        val admin = admin(store)

        admin.save(draft(url = "  https://example.test/v1 ", model = " m "))
        val saved = store.configs.getValue("p1")

        assertEquals("https://example.test/v1", saved.baseUrl)
        assertEquals(listOf("m"), store.models("p1").map { it.modelId })
        assertFalse(saved.toString().contains(SECRET))
        assertEquals(SECRET, store.secrets[saved.apiKey.value])

        admin.save(draft(url = "https://other.test/v1", key = "", model = "m2", id = "p1")) // blank key keeps the old one
        assertEquals(SECRET, store.secrets[store.configs.getValue("p1").apiKey.value])
        assertFalse(admin.overview().toString().contains(SECRET))
    }

    @Test
    fun providerAdminNamesFromPresetOrHostAndAddsHttpsWhenTheSchemeIsMissing() = runTest {
        val store = MemoryStore()
        val admin = admin(store)

        admin.save(draft(url = "openrouter.ai/api/v1", presetId = "openrouter"))
        admin.save(draft(url = "http://localhost:11434/v1", key = "k"))
        admin.save(draft(url = "https://my.host/v1", key = "k", name = " Mine "))

        assertEquals("https://openrouter.ai/api/v1", store.configs.getValue("p1").baseUrl)
        assertEquals(listOf("OpenRouter", "localhost:11434", "Mine"), store.configs.values.map { it.name })
        assertEquals("http://localhost:11434/v1", store.configs.getValue("p2").baseUrl)
    }

    @Test
    fun providerAdminTrimsWhitespaceAroundAPastedKey() = runTest {
        val store = MemoryStore()
        admin(store).save(draft(key = "  $SECRET\n"))
        assertEquals(SECRET, store.secrets[store.configs.getValue("p1").apiKey.value])
    }

    @Test
    fun providerAdminRejectsIncompleteInputAndStoresNothing() = runTest {
        val store = MemoryStore()
        val admin = admin(store)

        assertEquals(ProviderSaveResult.INCOMPLETE, admin.save(draft(url = "")))
        assertEquals(ProviderSaveResult.INCOMPLETE, admin.save(draft(model = " ")))
        assertEquals(ProviderSaveResult.INCOMPLETE, admin.save(draft(key = " ")))

        assertTrue(store.configs.isEmpty())
        assertTrue(store.secrets.isEmpty())
        assertEquals(null, store.active())
    }

    @Test
    fun aSavedProviderWhoseKeyIsGoneNeedsANewKeyToSaveOrTest() = runTest {
        val store = MemoryStore()
        val engine = MockEngine { respond(okStream) }
        val admin = admin(store, engine)
        admin.save(draft())
        store.secrets.clear() // e.g. the secret file was lost

        assertEquals(ProviderSaveResult.INCOMPLETE, admin.save(draft(key = "", id = "p1", model = "m2")))
        assertEquals(ConnectionTestResult.Incomplete, admin.testConnection(draft(key = "", id = "p1")))
        assertTrue(engine.requestHistory.isEmpty())
        assertEquals(ProviderSaveResult.SAVED, admin.save(draft(key = "sk-new", id = "p1", model = "m2")))
    }

    private val anthropicStream = "event: message_start\n" +
        """data: {"type":"message_start","message":{"usage":{"input_tokens":3}}}""" + "\n\n" +
        "event: content_block_delta\n" +
        """data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"hey"}}""" + "\n\n" +
        "event: message_stop\n" + """data: {"type":"message_stop"}""" + "\n\n"

    @Test
    fun theRouterUsesTheAdapterOfTheActiveProvidersKind() = runTest {
        val store = MemoryStore()
        val engine = MockEngine { request ->
            respond(if (request.url.host == "api.anthropic.com") anthropicStream else okStream)
        }
        val admin = admin(store)
        admin.save(draft(url = "https://one.test/v1", model = "m1")) // OpenAI-compatible, first: active
        admin.save(draft(url = "https://api.anthropic.com/v1", model = "claude-x", key = "sk-ant", kind = ProviderKind.ANTHROPIC))
        val router = LlmRouter(adaptersFor(engine), store, store)

        val first = router.stream(request).toList()
        admin.select("p2", "claude-x")
        val second = router.stream(request).toList()

        assertEquals(listOf(LlmChunk.Routed("p1", "m1"), LlmChunk.TextDelta("ok"), LlmChunk.Done), first)
        assertEquals(
            listOf(LlmChunk.Routed("p2", "claude-x"), LlmChunk.TextDelta("hey"), LlmChunk.Usage(3, 0), LlmChunk.Done),
            second,
        )
        val sent = engine.requestHistory
        assertEquals("https://one.test/v1/chat/completions", sent[0].url.toString())
        assertEquals("Bearer $SECRET", sent[0].headers[HttpHeaders.Authorization])
        assertEquals("https://api.anthropic.com/v1/messages", sent[1].url.toString())
        assertEquals("sk-ant", sent[1].headers["x-api-key"])
        assertEquals(null, sent[1].headers[HttpHeaders.Authorization])
    }

    @Test
    fun adminSavesTheKindAndUsesTheAnthropicFormatForModelListAndTest() = runTest {
        val store = MemoryStore()
        val engine = MockEngine { request ->
            if (request.url.encodedPath.endsWith("/models")) respond("""{"data":[{"id":"claude-a"}]}""") else respond(anthropicStream)
        }
        val admin = admin(store, engine)
        val anthropic = draft(url = "https://api.anthropic.com/v1", model = "claude-a", key = "sk-ant", presetId = "anthropic", kind = ProviderKind.ANTHROPIC)

        assertEquals(ModelListResult.Models(listOf("claude-a")), admin.listModels(anthropic))
        assertEquals(ConnectionTestResult.Success, admin.testConnection(anthropic))
        admin.save(anthropic)

        assertEquals(ProviderKind.ANTHROPIC, store.configs.getValue("p1").kind)
        assertEquals(listOf(ProviderKind.ANTHROPIC), admin.overview().providers.map { it.kind })
        assertTrue(engine.requestHistory.all { it.headers["x-api-key"] == "sk-ant" && it.headers[HttpHeaders.Authorization] == null })
    }

    @Test
    fun aWrongAnthropicKeyFailsTheTestWithAuthFailed() = runTest {
        val engine = MockEngine { respond("{}", HttpStatusCode.Unauthorized) }
        val result = admin(MemoryStore(), engine).testConnection(draft(kind = ProviderKind.ANTHROPIC))
        assertEquals(ConnectionTestResult.Failure(LlmError.AuthFailed), result)
    }

    @Test
    fun listModelsParsesTheProvidersModelIdsUsingTheKey() = runTest {
        val engine = MockEngine { respond("""{"data":[{"id":"a/one"},{"id":"b/two"},{"id":"a/one"}]}""") }

        val result = admin(MemoryStore(), engine).listModels(draft(url = "x.test/v1"))

        assertEquals(ModelListResult.Models(listOf("a/one", "b/two")), result)
        val sent = engine.requestHistory.single()
        assertEquals("https://x.test/v1/models", sent.url.toString())
        assertEquals("Bearer $SECRET", sent.headers[HttpHeaders.Authorization])
    }

    @Test
    fun listModelsFallsBackToManualEntryOnAnythingUnusable() = runTest {
        suspend fun result(engine: MockEngine) = admin(MemoryStore(), engine).listModels(draft())

        assertEquals(ModelListResult.Unavailable(LlmError.Server), result(MockEngine { respond("nope", HttpStatusCode.NotFound) }))
        assertEquals(ModelListResult.Unavailable(LlmError.AuthFailed), result(MockEngine { respond("{}", HttpStatusCode.Unauthorized) }))
        assertEquals(ModelListResult.Unavailable(null), result(MockEngine { respond("""{"data":[]}""") }))
        assertEquals(ModelListResult.Unavailable(LlmError.Server), result(MockEngine { respond("<html>") }))
        assertEquals(ModelListResult.Unavailable(LlmError.Network), result(MockEngine { throw IOException("down") }))
        assertEquals(ModelListResult.Unavailable(null), admin(MemoryStore()).listModels(draft(key = "")))
    }

    @Test
    fun testConnectionSucceedsOnTheFirstTextAndSendsOneChatRequest() = runTest {
        val engine = MockEngine { respond(okStream) }

        val result = admin(MemoryStore(), engine).testConnection(draft(model = "m"))

        assertEquals(ConnectionTestResult.Success, result)
        val sent = engine.requestHistory.single()
        assertEquals("https://x.test/v1/chat/completions", sent.url.toString())
        assertEquals("Bearer $SECRET", sent.headers[HttpHeaders.Authorization])
        assertFalse(sent.body.toByteArray().decodeToString().contains(SECRET))
    }

    @Test
    fun testConnectionReportsEachFailureReasonWithoutEchoingKeyOrBody() = runTest {
        suspend fun result(engine: MockEngine) = admin(MemoryStore(), engine).testConnection(draft())

        val body = "sk-leaked-in-body $SECRET"
        assertEquals(ConnectionTestResult.Failure(LlmError.AuthFailed), result(MockEngine { respond(body, HttpStatusCode.Unauthorized) }))
        assertEquals(ConnectionTestResult.Failure(LlmError.QuotaExhausted), result(MockEngine { respond(body, HttpStatusCode.PaymentRequired) }))
        assertEquals(ConnectionTestResult.Failure(LlmError.RateLimited(null)), result(MockEngine { respond(body, HttpStatusCode.TooManyRequests) }))
        assertEquals(ConnectionTestResult.Failure(LlmError.Server), result(MockEngine { respond(body, HttpStatusCode.BadGateway) }))
        assertEquals(ConnectionTestResult.Failure(LlmError.Network), result(MockEngine { throw IOException("down") }))
        assertFalse(ConnectionTestResult.Failure(LlmError.AuthFailed).toString().contains(SECRET))
    }

    @Test
    fun testConnectionNeedsUrlModelAndAKeyAndSendsNothingWithout() = runTest {
        val engine = MockEngine { respond(okStream) }
        val admin = admin(MemoryStore(), engine)

        assertEquals(ConnectionTestResult.Incomplete, admin.testConnection(draft(url = "")))
        assertEquals(ConnectionTestResult.Incomplete, admin.testConnection(draft(model = "")))
        assertEquals(ConnectionTestResult.Incomplete, admin.testConnection(draft(key = "")))
        assertTrue(engine.requestHistory.isEmpty())
    }

    @Test
    fun testConnectionOfASavedProviderWithABlankKeyUsesTheStoredKey() = runTest {
        val store = MemoryStore()
        val engine = MockEngine { respond(okStream) }
        val admin = admin(store, engine)
        admin.save(draft())

        val result = admin.testConnection(draft(key = "", id = "p1"))

        assertEquals(ConnectionTestResult.Success, result)
        assertEquals("Bearer $SECRET", engine.requestHistory.single().headers[HttpHeaders.Authorization])
    }
}
