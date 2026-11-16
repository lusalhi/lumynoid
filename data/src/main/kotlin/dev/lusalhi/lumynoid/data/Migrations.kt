package dev.lusalhi.lumynoid.data

import androidx.room3.migration.Migration
import androidx.sqlite.execSQL
import dev.lusalhi.lumynoid.core.ConversationTitle
import dev.lusalhi.lumynoid.core.TaskEvent
import dev.lusalhi.lumynoid.core.TaskEventJson

/**
 * v1 kept one `provider_config` row with its model. v2 keeps providers, their models and the active
 * choice apart: the old row becomes a provider named "Provider", its model a `model_profile` row, and
 * both become the active model, so a user who upgrades keeps chatting with what they had.
 */
internal val MIGRATION_1_2 = Migration(1, 2) { connection ->
    connection.execSQL(
        "CREATE TABLE IF NOT EXISTS `provider_config_new` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, " +
            "`preset_id` TEXT, `base_url` TEXT NOT NULL, `secret_ref` TEXT NOT NULL, PRIMARY KEY(`id`))",
    )
    connection.execSQL(
        "CREATE TABLE IF NOT EXISTS `model_profile` (`provider_id` TEXT NOT NULL, `model_id` TEXT NOT NULL, " +
            "`supports_tools` INTEGER, PRIMARY KEY(`provider_id`, `model_id`))",
    )
    connection.execSQL(
        "CREATE TABLE IF NOT EXISTS `active_model` (`id` INTEGER NOT NULL, `provider_id` TEXT NOT NULL, " +
            "`model_id` TEXT NOT NULL, PRIMARY KEY(`id`))",
    )
    connection.execSQL(
        "INSERT INTO provider_config_new (id, name, preset_id, base_url, secret_ref) " +
            "SELECT id, 'Provider', NULL, base_url, secret_ref FROM provider_config",
    )
    connection.execSQL("INSERT INTO model_profile (provider_id, model_id, supports_tools) SELECT id, model, NULL FROM provider_config")
    connection.execSQL("INSERT INTO active_model (id, provider_id, model_id) SELECT 1, id, model FROM provider_config LIMIT 1")
    connection.execSQL("DROP TABLE provider_config")
    connection.execSQL("ALTER TABLE provider_config_new RENAME TO provider_config")
}

/** v3 records which wire format a provider speaks; every provider saved so far is OpenAI-compatible. */
internal val MIGRATION_2_3 = Migration(2, 3) { connection ->
    connection.execSQL("ALTER TABLE provider_config ADD COLUMN `kind` TEXT NOT NULL DEFAULT 'openai_compatible'")
}

/**
 * v4 adds a conversation title. Existing conversations are titled from their first user message,
 * decoded in Kotlin so the rule is the same [ConversationTitle] the app uses; events are untouched.
 */
internal val MIGRATION_3_4 = Migration(3, 4) { connection ->
    connection.execSQL("ALTER TABLE conversation ADD COLUMN `title` TEXT NOT NULL DEFAULT ''")
    val titles = linkedMapOf<String, String>()
    connection.prepare("SELECT conversation_id, payload FROM task_event ORDER BY seq").use { st ->
        while (st.step()) {
            val conversationId = st.getText(0)
            if (conversationId in titles) continue
            val event = runCatching { TaskEventJson.decode(st.getText(1)) }.getOrNull()
            if (event is TaskEvent.UserMessage) titles[conversationId] = ConversationTitle.from(event.text)
        }
    }
    for ((id, title) in titles) {
        connection.prepare("UPDATE conversation SET title = ? WHERE id = ?").use { st ->
            st.bindText(1, title)
            st.bindText(2, id)
            st.step()
        }
    }
}
