package dev.lusalhi.lumynoid.llm

import dev.lusalhi.lumynoid.core.ActiveModel
import dev.lusalhi.lumynoid.core.ConnectionTestResult
import dev.lusalhi.lumynoid.core.LlmError
import dev.lusalhi.lumynoid.core.ModelListResult
import dev.lusalhi.lumynoid.core.ProviderKind
import dev.lusalhi.lumynoid.core.ProviderSaveResult
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProviderAdminTest {
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
