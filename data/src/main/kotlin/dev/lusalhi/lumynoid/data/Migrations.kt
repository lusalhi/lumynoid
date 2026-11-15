package dev.lusalhi.lumynoid.data

import androidx.room3.migration.Migration
import androidx.sqlite.execSQL

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
