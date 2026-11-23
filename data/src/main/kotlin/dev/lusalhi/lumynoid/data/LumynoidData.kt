package dev.lusalhi.lumynoid.data

import android.content.Context
import android.content.SharedPreferences
import androidx.room3.Room
import androidx.sqlite.driver.AndroidSQLiteDriver
import dev.lusalhi.lumynoid.core.Clock
import dev.lusalhi.lumynoid.core.ConversationPort
import dev.lusalhi.lumynoid.core.DEFAULT_MAX_LLM_CALLS
import dev.lusalhi.lumynoid.core.EventRepository
import dev.lusalhi.lumynoid.core.IdGenerator
import dev.lusalhi.lumynoid.core.ProjectPort
import dev.lusalhi.lumynoid.core.ProviderConfigStore
import dev.lusalhi.lumynoid.core.SecretPort
import dev.lusalhi.lumynoid.core.SystemClock
import dev.lusalhi.lumynoid.core.TaskEvent
import dev.lusalhi.lumynoid.core.TaskSettings
import dev.lusalhi.lumynoid.core.UuidV7
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Entry point of `:data`: opens the single database and exposes its ports.
 *
 * Both lifecycle callbacks share the runner's admission gate. Workspace cleanup is supplied by userland.
 */
class LumynoidData(
    context: Context,
    ids: IdGenerator = UuidV7,
    clock: Clock = SystemClock,
    deleteWhenIdle: suspend (String, suspend () -> Unit) -> Boolean,
    withLifecycle: suspend (suspend (Set<String>) -> Unit) -> Unit,
    deleteWorkspace: suspend (String) -> Unit,
) : AutoCloseable {
    private val appContext = context.applicationContext
    private val database: LumynoidDatabase =
        Room.databaseBuilder(appContext, LumynoidDatabase::class.java, "lumynoid.db")
            .setDriver(AndroidSQLiteDriver())
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7)
            .build()
    private val blobs = EventBlobStore(File(appContext.filesDir, "event-blobs"))

    private val eventRepository = RoomEventRepository(database.dao(), blobs)
    val events: EventRepository = eventRepository
    private val conversationRepository = RoomConversationPort(database.dao(), ids, clock, blobs, withLifecycle, deleteWhenIdle)
    val conversations: ConversationPort = conversationRepository
    val projects: ProjectPort = RoomProjectPort(database.dao(), ids, conversationRepository, withLifecycle, deleteWorkspace)
    val providerConfigs: ProviderConfigStore = RoomProviderConfigStore(database.dao())
    val secrets: SecretPort = KeystoreSecretStore(appContext)
    val taskSettings: TaskSettings = PreferenceTaskSettings(appContext.getSharedPreferences("task_settings", Context.MODE_PRIVATE))

    /** Only the main process supplies the read-only probe. Null leaves that chat pending. */
    suspend fun adoptLegacyWorkspaces(untitledProject: String, hasData: suspend (String) -> Boolean?) =
        conversationRepository.adoptLegacyWorkspaces(untitledProject, hasData)

    /** Opens a single persisted event without reading unrelated conversation blobs. */
    suspend fun event(conversationId: String, eventId: String): TaskEvent? = eventRepository.event(conversationId, eventId)

    override fun close() = database.close()
}

private class PreferenceTaskSettings(private val preferences: SharedPreferences) : TaskSettings {
    private val lock = Mutex()
    @Volatile
    private var savedLimit: Int? = null

    override suspend fun maxLlmCalls(): Int = savedLimit ?: withContext(Dispatchers.IO) {
        lock.withLock { savedLimit ?: readLimit().also { savedLimit = it } }
    }

    override suspend fun saveMaxLlmCalls(limit: Int): Boolean {
        require(limit > 0) { "The LLM call limit must be positive" }
        return withContext(Dispatchers.IO) {
            lock.withLock {
                // SharedPreferences changes its in-memory value even if commit fails to write to disk.
                if (savedLimit == null) savedLimit = readLimit()
                val committed = preferences.edit().putInt(MAX_LLM_CALLS, limit).commit()
                if (committed) savedLimit = limit
                committed
            }
        }
    }

    private fun readLimit(): Int =
        preferences.getInt(MAX_LLM_CALLS, DEFAULT_MAX_LLM_CALLS).takeIf { it > 0 } ?: DEFAULT_MAX_LLM_CALLS

    private companion object {
        const val MAX_LLM_CALLS = "max_llm_calls"
    }
}
