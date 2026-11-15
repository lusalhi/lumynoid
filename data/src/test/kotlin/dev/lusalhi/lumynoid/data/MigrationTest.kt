package dev.lusalhi.lumynoid.data

import androidx.room3.testing.MigrationTestHelper
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import dev.lusalhi.lumynoid.core.Origin
import dev.lusalhi.lumynoid.core.TaskEvent
import dev.lusalhi.lumynoid.core.TaskEventJson
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/** AD-19: every version bump has an explicit Migration, proved on the exported schema by [MigrationTestHelper]. */
class MigrationTest {

    // The helper is itself a JUnit rule; its database file lives in a throw-away directory of its own.
    @get:Rule
    val helper = MigrationTestHelper(
        schemaDirectoryPath = Path.of("schemas"),
        databasePath = Files.createTempDirectory("migration").resolve("migration.db"),
        driver = BundledSQLiteDriver(),
        databaseClass = LumynoidDatabase::class,
        databaseFactory = { LumynoidDatabase_Impl() },
    )

    private fun SQLiteConnection.rows(sql: String): List<List<String?>> = prepare(sql).use { st ->
        buildList {
            while (st.step()) add((0 until st.getColumnCount()).map { if (st.isNull(it)) null else st.getText(it) })
        }
    }

    @Test
    fun theStoryOneProviderSurvivesAsAProviderItsModelAndTheActiveChoice() = runTest {
        helper.createDatabase(1).use { v1 ->
            v1.execSQL(
                "INSERT INTO provider_config (id, base_url, model, secret_ref) " +
                    "VALUES ('p1', 'https://openrouter.ai/api/v1', 'openai/gpt-4o-mini', 'provider_key_p1')",
            )
        }

        val v2 = helper.runMigrationsAndValidate(2, listOf(MIGRATION_1_2))

        assertEquals(
            listOf(listOf<String?>("p1", "Provider", null, "https://openrouter.ai/api/v1", "provider_key_p1")),
            v2.rows("SELECT id, name, preset_id, base_url, secret_ref FROM provider_config"),
        )
        assertEquals(
            listOf(listOf<String?>("p1", "openai/gpt-4o-mini", null)),
            v2.rows("SELECT provider_id, model_id, supports_tools FROM model_profile"),
        )
        assertEquals(
            listOf(listOf<String?>("1", "p1", "openai/gpt-4o-mini")),
            v2.rows("SELECT id, provider_id, model_id FROM active_model"),
        )
        assertFalse(v2.rows("SELECT name FROM pragma_table_info('provider_config')").flatten().contains("model"))
    }

    @Test
    fun aV1DatabaseWithoutAProviderMigratesToEmptyProviderTables() = runTest {
        helper.createDatabase(1).close()

        val v2 = helper.runMigrationsAndValidate(2, listOf(MIGRATION_1_2))

        assertEquals(emptyList(), v2.rows("SELECT * FROM provider_config"))
        assertEquals(emptyList(), v2.rows("SELECT * FROM active_model"))
    }

    @Test
    fun v2ProvidersBecomeOpenAiCompatibleAndKeepTheirActiveModel() = runTest {
        helper.createDatabase(2).use { v2 ->
            v2.execSQL(
                "INSERT INTO provider_config (id, name, preset_id, base_url, secret_ref) VALUES " +
                    "('p1', 'OpenRouter', 'openrouter', 'https://openrouter.ai/api/v1', 'k1'), " +
                    "('p2', 'Mine', NULL, 'https://my.host/v1', 'k2')",
            )
            v2.execSQL("INSERT INTO model_profile (provider_id, model_id, supports_tools) VALUES ('p2', 'm', NULL)")
            v2.execSQL("INSERT INTO active_model (id, provider_id, model_id) VALUES (1, 'p2', 'm')")
        }

        val v3 = helper.runMigrationsAndValidate(3, listOf(MIGRATION_2_3))

        assertEquals(
            listOf(
                listOf<String?>("p1", "OpenRouter", "openrouter", "https://openrouter.ai/api/v1", "k1", "openai_compatible"),
                listOf<String?>("p2", "Mine", null, "https://my.host/v1", "k2", "openai_compatible"),
            ),
            v3.rows("SELECT id, name, preset_id, base_url, secret_ref, kind FROM provider_config ORDER BY id"),
        )
        assertEquals(listOf(listOf<String?>("p2", "m")), v3.rows("SELECT provider_id, model_id FROM model_profile"))
        assertEquals(listOf(listOf<String?>("1", "p2", "m")), v3.rows("SELECT id, provider_id, model_id FROM active_model"))
    }

    @Test
    fun theWholeChainFromV1ReachesV3() = runTest {
        helper.createDatabase(1).use { v1 ->
            v1.execSQL(
                "INSERT INTO provider_config (id, base_url, model, secret_ref) VALUES ('p1', 'https://x.test/v1', 'm', 'k1')",
            )
        }

        val v3 = helper.runMigrationsAndValidate(3, listOf(MIGRATION_1_2, MIGRATION_2_3))

        assertEquals(listOf(listOf<String?>("p1", "openai_compatible")), v3.rows("SELECT id, kind FROM provider_config"))
        assertEquals(listOf(listOf<String?>("p1", "m")), v3.rows("SELECT provider_id, model_id FROM active_model"))
    }

    @Test
    fun v3ConversationsAreTitledFromTheirFirstUserMessageAndEventsAreUntouched() = runTest {
        val first = TaskEventJson.encode(TaskEvent.UserMessage("e1", "t1", 5L, Origin.User, "\n  Plan   my trip\nto Bali"))
        val reply = TaskEventJson.encode(TaskEvent.AssistantMessage("e2", "t1", 6L, "sure"))
        val second = TaskEventJson.encode(TaskEvent.UserMessage("e3", "t2", 7L, Origin.User, "later"))
        helper.createDatabase(3).use { v3 ->
            v3.execSQL("INSERT INTO conversation (id, created_at) VALUES ('c1', 1), ('c2', 2)")
            v3.execSQL("INSERT INTO task_event (seq, id, task_id, conversation_id, at, payload) VALUES (1, 'e1', 't1', 'c1', 5, '${first.replace("'", "''")}')")
            v3.execSQL("INSERT INTO task_event (seq, id, task_id, conversation_id, at, payload) VALUES (2, 'e2', 't1', 'c1', 6, '${reply.replace("'", "''")}')")
            v3.execSQL("INSERT INTO task_event (seq, id, task_id, conversation_id, at, payload) VALUES (3, 'e3', 't2', 'c1', 7, '${second.replace("'", "''")}')")
        }

        val v4 = helper.runMigrationsAndValidate(4, listOf(MIGRATION_3_4))

        assertEquals(
            listOf(listOf<String?>("c1", "Plan my trip"), listOf<String?>("c2", "")),
            v4.rows("SELECT id, title FROM conversation ORDER BY id"),
        )
        assertEquals(listOf("e1", "e2", "e3"), v4.rows("SELECT id FROM task_event ORDER BY seq").flatten())
    }
}
