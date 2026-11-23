package dev.lusalhi.lumynoid.data

import dev.lusalhi.lumynoid.core.ActiveModel
import dev.lusalhi.lumynoid.core.Clock
import dev.lusalhi.lumynoid.core.Conversation
import dev.lusalhi.lumynoid.core.ConversationPort
import dev.lusalhi.lumynoid.core.ConversationTitle
import dev.lusalhi.lumynoid.core.ConversationWorkspace
import dev.lusalhi.lumynoid.core.EventRepository
import dev.lusalhi.lumynoid.core.IdGenerator
import dev.lusalhi.lumynoid.core.ModelProfile
import dev.lusalhi.lumynoid.core.Origin
import dev.lusalhi.lumynoid.core.Project
import dev.lusalhi.lumynoid.core.ProjectPort
import dev.lusalhi.lumynoid.core.ProviderConfig
import dev.lusalhi.lumynoid.core.ProviderConfigStore
import dev.lusalhi.lumynoid.core.ProviderKind
import dev.lusalhi.lumynoid.core.SecretRef
import dev.lusalhi.lumynoid.core.TaskEvent
import dev.lusalhi.lumynoid.core.TaskEventJson
import dev.lusalhi.lumynoid.core.TaskRequest
import dev.lusalhi.lumynoid.core.UnfinishedTask
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

internal class RoomEventRepository(
    private val dao: LumynoidDao,
    private val blobs: EventBlobStore,
) : EventRepository {

    override suspend fun createTask(taskId: String, request: TaskRequest, at: Long, workspaceId: String?) {
        val rowId = dao.insertTaskIfConversationExists(
            id = taskId,
            conversationId = request.conversationId,
            workspaceId = workspaceId,
            origin = TaskEventJson.json.encodeToString(Origin.serializer(), request.origin),
            createdAt = at,
        )
        check(rowId != -1L) { "Cannot create task for missing conversation: ${request.conversationId}" }
    }

    override suspend fun append(conversationId: String, event: TaskEvent) {
        val encoded = TaskEventJson.encode(event)
        // A UTF-16 code unit occupies at most three UTF-8 bytes.
        val bytes = if (encoded.length > StoredEventBlob.MAX_INLINE_BYTES / 3) encoded.encodeToByteArray() else null
        var blob: EventBlobRef? = null
        val payload = try {
            if (bytes != null && bytes.size > StoredEventBlob.MAX_INLINE_BYTES) {
                withContext(Dispatchers.IO) {
                    val ref = blobs.write(conversationId, bytes)
                    blob = ref
                    StoredEventBlob.encode(ref, event, encoded)
                }
            } else {
                encoded
            }
        } catch (e: Exception) {
            // The insert never ran, so nothing committed can reference the unpublished blob.
            discardUnpublished(conversationId, blob)
            throw e
        }
        val rowId = try {
            dao.insertEventIfConversationExists(
                id = event.id,
                taskId = event.taskId,
                conversationId = conversationId,
                at = event.at,
                payload = payload,
            )
        } catch (e: CancellationException) {
            // The insert may already have committed; a referenced blob must survive cancellation.
            throw e
        } catch (e: Exception) {
            discardUnpublished(conversationId, blob)
            throw e
        }
        if (rowId == -1L) discardUnpublished(conversationId, blob)
        if (event is TaskEvent.ToolCallRequested || event is TaskEvent.ToolResult ||
            event is TaskEvent.ApprovalRequested || event is TaskEvent.ApprovalResolved ||
            event is TaskEvent.ToolCallRejected || event is TaskEvent.ToolExecutionDenied ||
            event is TaskEvent.LlmRetry
        ) {
            check(rowId != -1L) { "Cannot append required event to missing conversation: $conversationId" }
        }
    }

    /** Best-effort removal of a blob whose row never committed; the original outcome stays authoritative. */
    private suspend fun discardUnpublished(conversationId: String, ref: EventBlobRef?) {
        if (ref == null) return
        runCatching { withContext(NonCancellable + Dispatchers.IO) { blobs.discard(conversationId, ref) } }
    }

    override fun observe(conversationId: String): Flow<List<TaskEvent>> =
        dao.observeEvents(conversationId).map { rows -> rows.map { it.toEvent() } }

    override suspend fun history(conversationId: String): List<TaskEvent> =
        dao.events(conversationId).map { it.toEvent() }

    suspend fun event(conversationId: String, eventId: String): TaskEvent? =
        dao.event(conversationId, eventId)?.toEvent()

    override suspend fun unfinishedTasks(): List<UnfinishedTask> =
        dao.taskLastEvents().filter { row ->
            val payload = row.lastPayload ?: return@filter true
            // Recovery only needs the row metadata, even when the last event's blob has gone missing.
            runCatching {
                if (StoredEventBlob.isEnvelope(payload)) {
                    StoredEventBlob.decode(payload).needsRecovery
                } else {
                    TaskEventJson.decode(payload).needsRecovery
                }
            }.getOrDefault(false)
        }.map { UnfinishedTask(it.taskId, it.conversationId) }

    private suspend fun TaskEventEntity.toEvent(): TaskEvent = try {
        if (StoredEventBlob.isEnvelope(payload)) {
            withContext(Dispatchers.IO) {
                val blob = StoredEventBlob.decode(payload)
                val encoded = blobs.read(conversationId, blob.ref)
                check(StoredEventBlob.eventType(encoded) == blob.eventType)
                TaskEventJson.decode(encoded).also {
                    check(it.id == id && it.taskId == taskId && it.at == at)
                }
            }
        } else {
            TaskEventJson.decode(payload)
        }
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        TaskEvent.Unknown(id, taskId, at)
    }
}

internal class RoomProjectPort(
    private val dao: LumynoidDao,
    private val ids: IdGenerator,
    private val conversations: RoomConversationPort,
    private val withLifecycle: suspend (suspend (Set<String>) -> Unit) -> Unit,
    private val deleteWorkspace: suspend (String) -> Unit,
) : ProjectPort {
    private val cleaning = ConcurrentHashMap.newKeySet<String>()

    override suspend fun create(name: String): Project? {
        val clean = name.trim()
        if (clean.isEmpty()) return null
        val id = ids.next()
        val project = ProjectEntity(id, clean, id, "")
        dao.insertProject(project)
        return project.toProject()
    }

    override fun observeAll(): Flow<List<Project>> = dao.observeProjects().map { rows -> rows.map { it.toProject() } }

    override suspend fun saveInstructions(id: String, instructions: String): Boolean {
        if (instructions.codePointCount(0, instructions.length) > ProjectPort.MAX_INSTRUCTION_CODE_POINTS) return false
        return dao.setProjectInstructions(id, instructions) > 0
    }

    override suspend fun instructionsForWorkspace(workspaceId: String): String? = dao.projectInstructions(workspaceId)

    override suspend fun delete(id: String): Boolean {
        if (!cleaning.add(id)) return false
        try {
            var target: ProjectEntity? = null
            var busy = false
            withLifecycle { pending ->
                val project = dao.project(id) ?: return@withLifecycle
                if (!project.deleting) {
                    if (pending.isNotEmpty() && dao.projectConversationIds(id).any { it in pending }) {
                        busy = true
                        return@withLifecycle
                    }
                    dao.markProjectDeleting(id)
                }
                target = project
            }
            if (busy) return false
            val project = target ?: return true
            for (chatId in dao.projectConversationIds(id)) conversations.deleteOwnedData(chatId)
            deleteWorkspace(project.workspaceId)
            dao.deleteProject(id)
            return true
        } finally {
            cleaning.remove(id)
        }
    }

    private fun ProjectEntity.toProject() = Project(id, name, workspaceId, instructions, deleting)
}

internal class RoomConversationPort(
    private val dao: LumynoidDao,
    private val ids: IdGenerator,
    private val clock: Clock,
    private val blobs: EventBlobStore,
    private val withLifecycle: suspend (suspend (Set<String>) -> Unit) -> Unit,
    private val deleteWhenIdle: suspend (String, suspend () -> Unit) -> Boolean,
) : ConversationPort {

    private val legacyAdoption = Mutex()

    suspend fun adoptLegacyWorkspaces(untitledProject: String, hasData: suspend (String) -> Boolean?) {
        legacyAdoption.withLock {
            for (id in dao.pendingLegacyConversations()) {
                val result = hasData(id) ?: continue // Unreadable: leave pending and retry on next foreground entry.
                withLifecycle { dao.adoptLegacyWorkspace(id, result, untitledProject) }
            }
        }
    }

    override suspend fun create(firstMessage: String, projectId: String?): Conversation {
        val now = clock.nowMillis()
        val conversation = Conversation(ids.next(), ConversationTitle.from(firstMessage), now, now, projectId)
        val row = ConversationEntity(conversation.id, conversation.createdAt, conversation.title, projectId)
        if (projectId == null) {
            dao.insertConversation(row)
        } else {
            withLifecycle {
                check(dao.isProjectActive(projectId)) { "Project unavailable" }
                dao.insertConversation(row)
            }
        }
        return conversation
    }

    override suspend fun workspace(id: String): ConversationWorkspace? {
        val row = dao.conversationWorkspace(id) ?: return null
        if (!row.hasResolvedScope()) return null
        if (row.projectId == null) return ConversationWorkspace.Plain
        return row.workspaceId?.let { ConversationWorkspace.Project(it) }
    }

    override suspend fun latest(): Conversation? = dao.latestConversation()?.toConversation()

    override fun observeAll(): Flow<List<Conversation>> =
        dao.observeConversations().map { rows -> rows.map { it.toConversation() } }

    override suspend fun rename(id: String, title: String): Boolean {
        val clean = title.trim()
        if (clean.isEmpty()) return false
        return dao.setTitle(id, clean) > 0
    }

    /**
     * Removes only chat-owned data. Shared project workspaces and unadopted legacy files are not
     * owned by this deletion. The idle gate keeps new submissions out through cleanup.
     */
    override suspend fun delete(id: String): Boolean {
        var classified = true
        val idle = deleteWhenIdle(id) {
            val row = dao.conversationWorkspace(id) ?: return@deleteWhenIdle
            if (!row.hasResolvedScope()) {
                classified = false
                return@deleteWhenIdle
            }
            deleteOwnedData(id)
        }
        return idle && classified
    }

    // Keep the row discoverable for retry until its on-disk blobs are gone.
    suspend fun deleteOwnedData(id: String) {
        withContext(Dispatchers.IO) { blobs.deleteConversation(id) }
        dao.deleteConversation(id)
    }

    private fun ConversationWorkspaceRow.hasResolvedScope(): Boolean =
        !legacyWorkspacePending && (projectId == null || workspaceId != null)

    private fun ConversationRow.toConversation() = Conversation(id, title, createdAt, lastActivityAt, projectId)
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
