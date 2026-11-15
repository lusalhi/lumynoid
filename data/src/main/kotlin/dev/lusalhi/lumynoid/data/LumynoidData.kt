package dev.lusalhi.lumynoid.data

import android.content.Context
import androidx.room3.Room
import androidx.sqlite.driver.AndroidSQLiteDriver
import dev.lusalhi.lumynoid.core.Clock
import dev.lusalhi.lumynoid.core.ConversationPort
import dev.lusalhi.lumynoid.core.EventRepository
import dev.lusalhi.lumynoid.core.IdGenerator
import dev.lusalhi.lumynoid.core.ProviderConfigStore
import dev.lusalhi.lumynoid.core.SecretPort
import dev.lusalhi.lumynoid.core.SystemClock
import dev.lusalhi.lumynoid.core.UuidV7

/** Entry point of `:data`: opens the single database and exposes its ports. */
class LumynoidData(
    context: Context,
    ids: IdGenerator = UuidV7,
    clock: Clock = SystemClock,
) {
    private val appContext = context.applicationContext
    private val database: LumynoidDatabase =
        Room.databaseBuilder(appContext, LumynoidDatabase::class.java, "lumynoid.db")
            .setDriver(AndroidSQLiteDriver())
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4) // no destructive fallback (AD-19)
            .build()

    val events: EventRepository = RoomEventRepository(database.dao())
    val conversations: ConversationPort = RoomConversationPort(database.dao(), ids, clock)
    val providerConfigs: ProviderConfigStore = RoomProviderConfigStore(database.dao())
    val secrets: SecretPort = KeystoreSecretStore(appContext)
}
