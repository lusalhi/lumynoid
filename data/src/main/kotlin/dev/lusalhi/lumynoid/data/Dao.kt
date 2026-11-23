package dev.lusalhi.lumynoid.data

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import androidx.room3.Transaction
import kotlinx.coroutines.flow.Flow

/** Most recent activity first; shared by `latest()` and the observed list. */
private const val CONVERSATION_ROWS =
    "SELECT c.id AS id, c.title AS title, c.created_at AS created_at, c.project_id AS project_id, " +
        "COALESCE((SELECT MAX(e.at) FROM task_event e WHERE e.conversation_id = c.id), c.created_at) AS last_activity_at " +
        "FROM conversation c LEFT JOIN project p ON p.id = c.project_id " +
        "WHERE c.project_id IS NULL OR p.deleting = 0 ORDER BY last_activity_at DESC, c.id DESC"

@Dao
internal interface LumynoidDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertProject(project: ProjectEntity)

    @Query("SELECT * FROM project ORDER BY name COLLATE NOCASE, id")
    fun observeProjects(): Flow<List<ProjectEntity>>

    @Query("SELECT * FROM project WHERE id = :id")
    suspend fun project(id: String): ProjectEntity?

    @Query("SELECT EXISTS(SELECT 1 FROM project WHERE id = :id AND deleting = 0)")
    suspend fun isProjectActive(id: String): Boolean

    @Query("SELECT id FROM conversation WHERE project_id = :projectId")
    suspend fun projectConversationIds(projectId: String): List<String>

    @Query("UPDATE project SET deleting = 1 WHERE id = :id")
    suspend fun markProjectDeleting(id: String)

    @Query("DELETE FROM project WHERE id = :id AND deleting = 1")
    suspend fun deleteProject(id: String)

    @Query("UPDATE project SET instructions = :instructions WHERE id = :id AND deleting = 0")
    suspend fun setProjectInstructions(id: String, instructions: String): Int

    /** At most one row: `workspace_id` is unique. Deleting projects no longer supply scope. */
    @Query("SELECT instructions FROM project WHERE workspace_id = :workspaceId AND deleting = 0")
    suspend fun projectInstructions(workspaceId: String): String?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertConversation(conversation: ConversationEntity)

    @Query("$CONVERSATION_ROWS LIMIT 1")
    suspend fun latestConversation(): ConversationRow?

    @Query(CONVERSATION_ROWS)
    fun observeConversations(): Flow<List<ConversationRow>>

    @Query(
        "SELECT c.project_id, p.workspace_id, c.legacy_workspace_pending FROM conversation c " +
            "LEFT JOIN project p ON p.id = c.project_id AND p.deleting = 0 WHERE c.id = :id",
    )
    suspend fun conversationWorkspace(id: String): ConversationWorkspaceRow?

    @Query("UPDATE conversation SET title = :title WHERE id = :id")
    suspend fun setTitle(id: String, title: String): Int

    @Query("SELECT id FROM conversation WHERE legacy_workspace_pending = 1 ORDER BY id")
    suspend fun pendingLegacyConversations(): List<String>

    @Query("SELECT title FROM conversation WHERE id = :id AND legacy_workspace_pending = 1")
    suspend fun pendingLegacyTitle(id: String): String?

    @Query("UPDATE conversation SET legacy_workspace_pending = 0, project_id = :projectId WHERE id = :id AND legacy_workspace_pending = 1")
    suspend fun settleLegacyWorkspace(id: String, projectId: String?): Int

    /** Publishes membership and clears the gate together, using the latest title. */
    @Transaction
    suspend fun adoptLegacyWorkspace(id: String, hasData: Boolean, untitledProject: String) {
        val title = pendingLegacyTitle(id) ?: return
        val projectId = if (hasData) {
            insertProject(ProjectEntity(id, title.ifBlank { untitledProject }, id, ""))
            id
        } else null
        check(settleLegacyWorkspace(id, projectId) == 1)
    }

    @Query("DELETE FROM task_event WHERE conversation_id = :id")
    suspend fun deleteEvents(id: String)

    @Query("DELETE FROM task WHERE conversation_id = :id")
    suspend fun deleteTasks(id: String)

    @Query("DELETE FROM conversation WHERE id = :id")
    suspend fun deleteConversationRow(id: String)

    /** Events, tasks and the conversation go together or not at all. */
    @Transaction
    suspend fun deleteConversation(id: String) {
        deleteEvents(id)
        deleteTasks(id)
        deleteConversationRow(id)
    }

    @Query("SELECT COUNT(*) FROM task")
    suspend fun taskCount(): Int

    /** Writes only while the conversation row exists, atomically against [deleteConversation]; -1 when it is gone. */
    @Query(
        "INSERT INTO task (id, conversation_id, workspace_id, origin, created_at) " +
            "SELECT :id, :conversationId, :workspaceId, :origin, :createdAt " +
            "WHERE EXISTS (SELECT 1 FROM conversation WHERE id = :conversationId)",
    )
    suspend fun insertTaskIfConversationExists(id: String, conversationId: String, workspaceId: String?, origin: String, createdAt: Long): Long

    /** Writes only while the conversation row exists, atomically against [deleteConversation]. */
    @Query(
        "INSERT INTO task_event (id, task_id, conversation_id, at, payload) " +
            "SELECT :id, :taskId, :conversationId, :at, :payload " +
            "WHERE EXISTS (SELECT 1 FROM conversation WHERE id = :conversationId)",
    )
    suspend fun insertEventIfConversationExists(id: String, taskId: String, conversationId: String, at: Long, payload: String): Long

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertEvent(event: TaskEventEntity)

    @Query("SELECT * FROM task_event WHERE conversation_id = :conversationId ORDER BY seq")
    suspend fun events(conversationId: String): List<TaskEventEntity>

    @Query("SELECT * FROM task_event WHERE id = :eventId AND conversation_id = :conversationId")
    suspend fun event(conversationId: String, eventId: String): TaskEventEntity?

    @Query("SELECT * FROM task_event WHERE conversation_id = :conversationId ORDER BY seq")
    fun observeEvents(conversationId: String): Flow<List<TaskEventEntity>>

    /** Every task with the payload of its last event (null when it has none). */
    @Query(
        "SELECT t.id AS task_id, t.conversation_id AS conversation_id, " +
            "(SELECT e.payload FROM task_event e WHERE e.conversation_id = t.conversation_id AND e.task_id = t.id " +
            "ORDER BY e.seq DESC LIMIT 1) AS last_payload FROM task t",
    )
    suspend fun taskLastEvents(): List<TaskLastEventRow>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertProvider(config: ProviderConfigEntity)

    @Query("SELECT * FROM provider_config ORDER BY rowid")
    suspend fun providers(): List<ProviderConfigEntity>

    @Query("SELECT * FROM provider_config WHERE id = :id")
    suspend fun provider(id: String): ProviderConfigEntity?

    /** REPLACE re-inserts the row, so `rowid` order is the order models were last saved. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertModel(model: ModelProfileEntity)

    @Query("SELECT * FROM model_profile WHERE provider_id = :providerId ORDER BY rowid")
    suspend fun models(providerId: String): List<ModelProfileEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertActive(active: ActiveModelEntity)

    @Query("SELECT * FROM active_model WHERE id = 1")
    suspend fun active(): ActiveModelEntity?
}
