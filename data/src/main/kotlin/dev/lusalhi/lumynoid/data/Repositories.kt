package dev.lusalhi.lumynoid.data

import dev.lusalhi.lumynoid.core.ActiveModel
import dev.lusalhi.lumynoid.core.Clock
import dev.lusalhi.lumynoid.core.Conversation
import dev.lusalhi.lumynoid.core.ConversationPort
import dev.lusalhi.lumynoid.core.ConversationTitle
import dev.lusalhi.lumynoid.core.EventRepository
import dev.lusalhi.lumynoid.core.IdGenerator
import dev.lusalhi.lumynoid.core.ModelProfile
import dev.lusalhi.lumynoid.core.Origin
import dev.lusalhi.lumynoid.core.ProviderConfig
import dev.lusalhi.lumynoid.core.ProviderConfigStore
import dev.lusalhi.lumynoid.core.ProviderKind
import dev.lusalhi.lumynoid.core.SecretRef
import dev.lusalhi.lumynoid.core.TaskEvent
import dev.lusalhi.lumynoid.core.TaskEventJson
import dev.lusalhi.lumynoid.core.TaskRequest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

internal class RoomEventRepository(private val dao: LumynoidDao) : EventRepository {

    override suspend fun createTask(taskId: String, request: TaskRequest, at: Long) {
        dao.insertTaskIfConversationExists(
            id = taskId,
            conversationId = request.conversationId,
            workspaceId = request.workspaceId,
            origin = TaskEventJson.json.encodeToString(Origin.serializer(), request.origin),
            createdAt = at,
        )
    }

    override suspend fun append(conversationId: String, event: TaskEvent) {
        dao.insertEventIfConversationExists(
            id = event.id,
            taskId = event.taskId,
            conversationId = conversationId,
            at = event.at,
            payload = TaskEventJson.encode(event),
        )
    }

    override fun observe(conversationId: String): Flow<List<TaskEvent>> =
        dao.observeEvents(conversationId).map { rows -> rows.map { it.toEvent() } }

    override suspend fun history(conversationId: String): List<TaskEvent> =
        dao.events(conversationId).map { it.toEvent() }

    private fun TaskEventEntity.toEvent(): TaskEvent =
        runCatching { TaskEventJson.decode(payload) }.getOrDefault(TaskEvent.Unknown(id, taskId, at))
}

internal class RoomConversationPort(
    private val dao: LumynoidDao,
    private val ids: IdGenerator,
    private val clock: Clock,
) : ConversationPort {

    override suspend fun create(firstMessage: String): Conversation {
        val now = clock.nowMillis()
        val conversation = Conversation(ids.next(), ConversationTitle.from(firstMessage), now, now)
        dao.insertConversation(ConversationEntity(conversation.id, conversation.createdAt, conversation.title))
        return conversation
    }

    override suspend fun latest(): Conversation? = dao.latestConversation()?.toConversation()

    override fun observeAll(): Flow<List<Conversation>> =
        dao.observeConversations().map { rows -> rows.map { it.toConversation() } }

    override suspend fun rename(id: String, title: String): Boolean {
        val clean = title.trim()
        if (clean.isEmpty()) return false
        return dao.setTitle(id, clean) > 0
    }

    override suspend fun delete(id: String) = dao.deleteConversation(id)

    private fun ConversationRow.toConversation() = Conversation(id, title, createdAt, lastActivityAt)
}

internal class RoomProviderConfigStore(private val dao: LumynoidDao) : ProviderConfigStore {

    override suspend fun providers(): List<ProviderConfig> = dao.providers().map { it.toConfig() }

    override suspend fun provider(id: String): ProviderConfig? = dao.provider(id)?.toConfig()

    override suspend fun save(config: ProviderConfig) {
        dao.upsertProvider(
            ProviderConfigEntity(
                config.id, config.name, config.presetId, config.baseUrl, config.apiKey.value, config.kind.wireName,
            ),
        )
    }

    override suspend fun models(providerId: String): List<ModelProfile> =
        dao.models(providerId).map { ModelProfile(it.providerId, it.modelId, it.supportsTools) }

    override suspend fun saveModel(model: ModelProfile) {
        dao.upsertModel(ModelProfileEntity(model.providerId, model.modelId, model.supportsTools))
    }

    override suspend fun active(): ActiveModel? = dao.active()?.let { ActiveModel(it.providerId, it.modelId) }

    override suspend fun setActive(active: ActiveModel) {
        dao.upsertActive(ActiveModelEntity(providerId = active.providerId, modelId = active.modelId))
    }

    private fun ProviderConfigEntity.toConfig() = ProviderConfig(id, name, presetId, baseUrl, SecretRef(secretRef), ProviderKind.fromWireName(kind))
}
