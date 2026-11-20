package dev.lusalhi.lumynoid.llm

import dev.lusalhi.lumynoid.core.ActiveModel
import dev.lusalhi.lumynoid.core.LlmChunk
import dev.lusalhi.lumynoid.core.LlmError
import dev.lusalhi.lumynoid.core.ProviderKind
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpHeaders
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LlmRouterTest {
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
}
