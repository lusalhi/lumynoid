package dev.lusalhi.lumynoid.llm

import dev.lusalhi.lumynoid.core.ActiveModel
import dev.lusalhi.lumynoid.core.IdGenerator
import dev.lusalhi.lumynoid.core.LlmMessage
import dev.lusalhi.lumynoid.core.LlmRequest
import dev.lusalhi.lumynoid.core.ModelProfile
import dev.lusalhi.lumynoid.core.ProviderConfig
import dev.lusalhi.lumynoid.core.ProviderConfigStore
import dev.lusalhi.lumynoid.core.ProviderDraft
import dev.lusalhi.lumynoid.core.ProviderKind
import dev.lusalhi.lumynoid.core.Role
import dev.lusalhi.lumynoid.core.SecretPort
import dev.lusalhi.lumynoid.core.SecretRef
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond

internal const val SECRET = "sk-test-SECRET-123"

internal const val TEST_URL = "https://example.test/api/v1/"
internal const val TEST_MODEL = "some/model"
internal val request = LlmRequest(listOf(LlmMessage(Role.USER, "hi")))

internal class MemoryStore : ProviderConfigStore, SecretPort {
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

internal class CountingIds : IdGenerator {
    private var n = 0
    override fun next() = "p${++n}"
}

internal fun draft(
    url: String = "https://x.test/v1",
    key: String = SECRET,
    model: String = "m",
    id: String? = null,
    makeActive: Boolean = false,
    presetId: String? = null,
    name: String = "",
    kind: ProviderKind = ProviderKind.OPENAI_COMPATIBLE,
) = ProviderDraft(id, presetId, name, url, key, model, makeActive, kind)

internal fun sse(vararg lines: String) = lines.joinToString("\n\n", postfix = "\n\n")

internal fun adaptersFor(engine: MockEngine) = ProviderAdapters(HttpClient(engine))

internal fun admin(store: MemoryStore, engine: MockEngine = MockEngine { respond("never") }) =
    ProviderAdmin(adaptersFor(engine), store, store, CountingIds())

internal val okStream = sse("""data: {"choices":[{"delta":{"content":"ok"}}]}""", "data: [DONE]")

internal val anthropicStream = "event: message_start\n" +
    """data: {"type":"message_start","message":{"usage":{"input_tokens":3}}}""" + "\n\n" +
    "event: content_block_delta\n" +
    """data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"hey"}}""" + "\n\n" +
    "event: message_stop\n" + """data: {"type":"message_stop"}""" + "\n\n"
