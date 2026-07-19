package io.github.ayaseminami.gnbp.persistence

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.room.Room
import io.github.ayaseminami.gnbp.persistence.profile.ProfileRepository
import io.github.ayaseminami.gnbp.persistence.profile.RoomProfileRepository
import io.github.ayaseminami.gnbp.persistence.prompt.PromptRepository
import io.github.ayaseminami.gnbp.persistence.prompt.RoomPromptRepository
import io.github.ayaseminami.gnbp.persistence.room.GnbpDatabase
import io.github.ayaseminami.gnbp.persistence.secret.AesGcmSecretCipher
import io.github.ayaseminami.gnbp.persistence.secret.AndroidKeystoreSecretKeyProvider
import io.github.ayaseminami.gnbp.persistence.settings.DataStoreSettingsRepository
import io.github.ayaseminami.gnbp.persistence.settings.SettingsDataMigration
import io.github.ayaseminami.gnbp.persistence.settings.SettingsRepository
import io.github.ayaseminami.gnbp.generation.GenerationTaskRepository
import io.github.ayaseminami.gnbp.persistence.task.RoomGenerationTaskRepository
import java.io.Closeable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

class GnbpPersistence private constructor(
    private val database: GnbpDatabase,
    private val scope: CoroutineScope,
    val profiles: ProfileRepository,
    val prompts: PromptRepository,
    val settings: SettingsRepository,
    val tasks: GenerationTaskRepository,
) : Closeable {
    override fun close() {
        scope.cancel()
        database.close()
    }

    companion object {
        fun create(context: Context): GnbpPersistence {
            val applicationContext = context.applicationContext
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val database = Room.databaseBuilder(
                applicationContext,
                GnbpDatabase::class.java,
                DATABASE_NAME,
            )
                .addMigrations(GnbpDatabase.MIGRATION_1_2, GnbpDatabase.MIGRATION_2_3)
                .build()
            val cipher = AesGcmSecretCipher(AndroidKeystoreSecretKeyProvider())
            val dataStore = PreferenceDataStoreFactory.create(
                scope = scope,
                migrations = listOf(SettingsDataMigration()),
                produceFile = { applicationContext.preferencesDataStoreFile(SETTINGS_FILE_NAME) },
            )
            return GnbpPersistence(
                database = database,
                scope = scope,
                profiles = RoomProfileRepository(database.profileDao(), cipher),
                prompts = RoomPromptRepository(database.promptDao()),
                settings = DataStoreSettingsRepository(dataStore),
                tasks = RoomGenerationTaskRepository(database.taskDao()),
            )
        }

        private const val DATABASE_NAME = "gnbp.db"
        private const val SETTINGS_FILE_NAME = "gnbp-settings.preferences_pb"
    }
}
