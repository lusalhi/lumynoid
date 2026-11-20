package dev.lusalhi.lumynoid.llm

import dev.lusalhi.lumynoid.core.ActiveModel
import dev.lusalhi.lumynoid.core.EventRepository
import dev.lusalhi.lumynoid.core.IdGenerator
import dev.lusalhi.lumynoid.core.LlmMessage
import dev.lusalhi.lumynoid.core.LlmPort
import dev.lusalhi.lumynoid.core.LlmRequest
import dev.lusalhi.lumynoid.core.ModelProfile
import dev.lusalhi.lumynoid.core.Origin
import dev.lusalhi.lumynoid.core.ProviderConfig
import dev.lusalhi.lumynoid.core.ProviderConfigStore
import dev.lusalhi.lumynoid.core.ProviderDraft
import dev.lusalhi.lumynoid.core.ProviderKind
import dev.lusalhi.lumynoid.core.RiskAssessment
import dev.lusalhi.lumynoid.core.Role
import dev.lusalhi.lumynoid.core.SecretPort
import dev.lusalhi.lumynoid.core.SecretRef
import dev.lusalhi.lumynoid.core.TaskEvent
import dev.lusalhi.lumynoid.core.TaskRequest
import dev.lusalhi.lumynoid.core.TaskRunner
import dev.lusalhi.lumynoid.core.Tool
import dev.lusalhi.lumynoid.core.ToolContext
import dev.lusalhi.lumynoid.core.ToolOutcome
import dev.lusalhi.lumynoid.core.ToolProvider
import dev.lusalhi.lumynoid.core.ToolRegistry
import dev.lusalhi.lumynoid.core.UiText
import dev.lusalhi.lumynoid.core.UnfinishedTask
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

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

internal suspend fun MockEngine.requestJson(index: Int): JsonObject =
    wireJson.parseToJsonElement(requestHistory[index].body.toByteArray().decodeToString()).jsonObject

internal class ToolRunnerFixture(scope: CoroutineScope, llm: LlmPort) {
    private val events = MutableStateFlow<List<TaskEvent>>(emptyList())
    private val repository = object : EventRepository {
        override suspend fun createTask(taskId: String, request: TaskRequest, at: Long) = Unit
        override suspend fun append(conversationId: String, event: TaskEvent) {
            events.value += event
        }
        override fun observe(conversationId: String) = events
        override suspend fun history(conversationId: String) = events.value
        override suspend fun unfinishedTasks() = emptyList<UnfinishedTask>()
    }

    val tools: List<Tool> = listOf(
        FixtureTool(
            "web_lookup",
            "Look up a fixture",
            """{"type":"object","properties":{"key":{"type":"string"}},"required":["key"]}""",
        ) { args -> "fixture:${args.getValue("key").jsonPrimitive.content}" },
        FixtureTool(
            "web_read",
            "Read a fixture",
            """{"type":"object","properties":{"limit":{"type":"integer"}},"required":["limit"]}""",
        ) { "" },
    )
    private val runner = TaskRunner(llm, repository, scope, ids = CountingIds(), tools = ToolRegistry(listOf(ToolProvider { tools })))

    suspend fun run(input: String): List<TaskEvent> {
        val id = runner.submit(TaskRequest(Origin.User, "conversation", "workspace", input))
        return events.first { log -> log.any { it.taskId == id && !it.needsRecovery } }.filter { it.taskId == id }
    }

    private class FixtureTool(
        override val name: String,
        override val description: String,
        schema: String,
        private val output: (JsonObject) -> String,
    ) : Tool {
        override val inputSchema = wireJson.parseToJsonElement(schema).jsonObject
        override fun assess(args: JsonObject) = RiskAssessment.READ_ONLY
        override fun describe(args: JsonObject) = UiText("fixture")
        override suspend fun execute(args: JsonObject, context: ToolContext) = ToolOutcome(output(args))
    }
}

internal fun admin(store: MemoryStore, engine: MockEngine = MockEngine { respond("never") }) =
    ProviderAdmin(adaptersFor(engine), store, store, CountingIds())

internal val okStream = sse("""data: {"choices":[{"delta":{"content":"ok"}}]}""", "data: [DONE]")

internal val anthropicStream = "event: message_start\n" +
    """data: {"type":"message_start","message":{"usage":{"input_tokens":3}}}""" + "\n\n" +
    "event: content_block_delta\n" +
    """data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"hey"}}""" + "\n\n" +
    "event: message_stop\n" + """data: {"type":"message_stop"}""" + "\n\n"
