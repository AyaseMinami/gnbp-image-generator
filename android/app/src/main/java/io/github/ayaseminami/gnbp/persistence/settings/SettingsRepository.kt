package io.github.ayaseminami.gnbp.persistence.settings

import androidx.datastore.core.DataMigration
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import io.github.ayaseminami.gnbp.persistence.prompt.PromptId
import io.github.ayaseminami.gnbp.provider.transport.ProfileId
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

enum class GalleryLayoutMode {
    LargeGrid,
    CompactGrid,
    List,
}

data class AppSettings(
    val selectedProfileId: ProfileId? = null,
    val selectedPromptId: PromptId? = null,
    val batchCount: Int = DEFAULT_BATCH_COUNT,
    val maxConcurrency: Int = DEFAULT_MAX_CONCURRENCY,
    val showPreview: Boolean = DEFAULT_SHOW_PREVIEW,
    val completionNotifications: Boolean = DEFAULT_COMPLETION_NOTIFICATIONS,
    val soundNotification: Boolean = DEFAULT_SOUND_NOTIFICATION,
    val galleryLayoutMode: GalleryLayoutMode = DEFAULT_GALLERY_LAYOUT_MODE,
) {
    init {
        require(batchCount in 1..MAX_BATCH_COUNT) { "Batch count must be between 1 and $MAX_BATCH_COUNT" }
        require(maxConcurrency in 1..MAX_CONCURRENCY) {
            "Maximum concurrency must be between 1 and $MAX_CONCURRENCY"
        }
    }

    override fun toString(): String =
        "AppSettings(selectedProfileId=[REDACTED], selectedPromptId=[REDACTED], " +
            "batchCount=$batchCount, maxConcurrency=$maxConcurrency, showPreview=$showPreview, " +
            "completionNotifications=$completionNotifications, " +
            "soundNotification=$soundNotification, galleryLayoutMode=$galleryLayoutMode)"

    companion object {
        const val DEFAULT_BATCH_COUNT = 1
        const val DEFAULT_MAX_CONCURRENCY = 1
        const val DEFAULT_SHOW_PREVIEW = true
        const val DEFAULT_COMPLETION_NOTIFICATIONS = false
        const val DEFAULT_SOUND_NOTIFICATION = true
        val DEFAULT_GALLERY_LAYOUT_MODE = GalleryLayoutMode.LargeGrid
        const val MAX_BATCH_COUNT = 16
        const val MAX_CONCURRENCY = 2
    }
}

interface SettingsRepository {
    fun observeSettings(): Flow<AppSettings>

    suspend fun saveSettings(settings: AppSettings)
}

class DataStoreSettingsRepository(
    private val dataStore: DataStore<Preferences>,
) : SettingsRepository {
    override fun observeSettings(): Flow<AppSettings> = dataStore.data
        .catch { error ->
            if (error is IOException) emit(emptyPreferences()) else throw error
        }
        .map(Preferences::toAppSettings)

    override suspend fun saveSettings(settings: AppSettings) {
        dataStore.edit { preferences ->
            settings.selectedProfileId?.let { preferences[SettingsKeys.SELECTED_PROFILE_ID] = it.value }
                ?: preferences.remove(SettingsKeys.SELECTED_PROFILE_ID)
            settings.selectedPromptId?.let { preferences[SettingsKeys.SELECTED_PROMPT_ID] = it.value }
                ?: preferences.remove(SettingsKeys.SELECTED_PROMPT_ID)
            preferences[SettingsKeys.BATCH_COUNT] = settings.batchCount
            preferences[SettingsKeys.MAX_CONCURRENCY] = settings.maxConcurrency
            preferences[SettingsKeys.SHOW_PREVIEW] = settings.showPreview
            preferences[SettingsKeys.COMPLETION_NOTIFICATIONS] = settings.completionNotifications
            preferences[SettingsKeys.SOUND_NOTIFICATION] = settings.soundNotification
            preferences[SettingsKeys.GALLERY_LAYOUT_MODE] = settings.galleryLayoutMode.name
            preferences[SettingsKeys.SCHEMA_VERSION] = SettingsDataMigration.CURRENT_SCHEMA_VERSION
        }
    }
}

class SettingsDataMigration : DataMigration<Preferences> {
    override suspend fun shouldMigrate(currentData: Preferences): Boolean =
        currentData[SettingsKeys.SCHEMA_VERSION] != CURRENT_SCHEMA_VERSION ||
            currentData[SettingsKeys.BATCH_COUNT] == null ||
            currentData[SettingsKeys.MAX_CONCURRENCY] == null ||
            currentData[SettingsKeys.SHOW_PREVIEW] == null ||
            currentData[SettingsKeys.COMPLETION_NOTIFICATIONS] == null ||
            currentData[SettingsKeys.SOUND_NOTIFICATION] == null ||
            currentData[SettingsKeys.GALLERY_LAYOUT_MODE] == null

    override suspend fun migrate(currentData: Preferences): Preferences =
        currentData.toMutablePreferences().apply {
            if (this[SettingsKeys.BATCH_COUNT] == null) {
                this[SettingsKeys.BATCH_COUNT] = AppSettings.DEFAULT_BATCH_COUNT
            }
            if (this[SettingsKeys.MAX_CONCURRENCY] == null) {
                this[SettingsKeys.MAX_CONCURRENCY] = AppSettings.DEFAULT_MAX_CONCURRENCY
            }
            if (this[SettingsKeys.SHOW_PREVIEW] == null) {
                this[SettingsKeys.SHOW_PREVIEW] = AppSettings.DEFAULT_SHOW_PREVIEW
            }
            if (this[SettingsKeys.COMPLETION_NOTIFICATIONS] == null) {
                this[SettingsKeys.COMPLETION_NOTIFICATIONS] =
                    AppSettings.DEFAULT_COMPLETION_NOTIFICATIONS
            }
            if (this[SettingsKeys.SOUND_NOTIFICATION] == null) {
                this[SettingsKeys.SOUND_NOTIFICATION] = AppSettings.DEFAULT_SOUND_NOTIFICATION
            }
            if (this[SettingsKeys.GALLERY_LAYOUT_MODE] == null) {
                this[SettingsKeys.GALLERY_LAYOUT_MODE] = AppSettings.DEFAULT_GALLERY_LAYOUT_MODE.name
            }
            this[SettingsKeys.SCHEMA_VERSION] = CURRENT_SCHEMA_VERSION
        }

    override suspend fun cleanUp() = Unit

    companion object {
        const val CURRENT_SCHEMA_VERSION = 3
    }
}

internal object SettingsKeys {
    val SCHEMA_VERSION = intPreferencesKey("schema_version")
    val SELECTED_PROFILE_ID = stringPreferencesKey("selected_profile_id")
    val SELECTED_PROMPT_ID = stringPreferencesKey("selected_prompt_id")
    val BATCH_COUNT = intPreferencesKey("batch_count")
    val MAX_CONCURRENCY = intPreferencesKey("max_concurrency")
    val SHOW_PREVIEW = booleanPreferencesKey("show_preview")
    val COMPLETION_NOTIFICATIONS = booleanPreferencesKey("completion_notifications")
    val SOUND_NOTIFICATION = booleanPreferencesKey("sound_notification")
    val GALLERY_LAYOUT_MODE = stringPreferencesKey("gallery_layout_mode")
}

private fun Preferences.toAppSettings(): AppSettings = AppSettings(
    selectedProfileId = this[SettingsKeys.SELECTED_PROFILE_ID]?.let(::ProfileId),
    selectedPromptId = this[SettingsKeys.SELECTED_PROMPT_ID]?.let(::PromptId),
    batchCount = (this[SettingsKeys.BATCH_COUNT] ?: AppSettings.DEFAULT_BATCH_COUNT)
        .coerceIn(1, AppSettings.MAX_BATCH_COUNT),
    maxConcurrency = (this[SettingsKeys.MAX_CONCURRENCY] ?: AppSettings.DEFAULT_MAX_CONCURRENCY)
        .coerceIn(1, AppSettings.MAX_CONCURRENCY),
    showPreview = this[SettingsKeys.SHOW_PREVIEW] ?: AppSettings.DEFAULT_SHOW_PREVIEW,
    completionNotifications = this[SettingsKeys.COMPLETION_NOTIFICATIONS]
        ?: AppSettings.DEFAULT_COMPLETION_NOTIFICATIONS,
    soundNotification = this[SettingsKeys.SOUND_NOTIFICATION] ?: AppSettings.DEFAULT_SOUND_NOTIFICATION,
    galleryLayoutMode = this[SettingsKeys.GALLERY_LAYOUT_MODE]
        ?.let { raw -> GalleryLayoutMode.entries.firstOrNull { mode -> mode.name == raw } }
        ?: AppSettings.DEFAULT_GALLERY_LAYOUT_MODE,
)
