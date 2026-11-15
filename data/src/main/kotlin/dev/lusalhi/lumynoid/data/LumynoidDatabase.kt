package dev.lusalhi.lumynoid.data

import androidx.room3.Database
import androidx.room3.RoomDatabase

/** The single database (AD-19). Every version bump needs an explicit, tested Migration. */
@Database(
    entities = [
        ConversationEntity::class,
        TaskEntity::class,
        TaskEventEntity::class,
        ProviderConfigEntity::class,
        ModelProfileEntity::class,
        ActiveModelEntity::class,
    ],
    version = 2,
    exportSchema = true,
)
internal abstract class LumynoidDatabase : RoomDatabase() {
    abstract fun dao(): LumynoidDao
}
