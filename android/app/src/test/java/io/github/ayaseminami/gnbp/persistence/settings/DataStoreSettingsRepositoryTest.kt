package io.github.ayaseminami.gnbp.persistence.settings

import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.InterProcessCoordinator
import androidx.datastore.core.ReadScope
import androidx.datastore.core.Storage
import androidx.datastore.core.StorageConnection
import androidx.datastore.core.WriteScope
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import io.github.ayaseminami.gnbp.persistence.prompt.PromptId
import io.github.ayaseminami.gnbp.provider.transport.ProfileId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DataStoreSettingsRepositoryTest {
    @Test
    fun `settings string representation redacts persisted selections`() {
        val settings = AppSettings(
            selectedProfileId = ProfileId("private-profile-id"),
            selectedPromptId = PromptId("private-prompt-id"),
        )

        assertFalse(settings.toString().contains("private-profile-id"))
        assertFalse(settings.toString().contains("private-prompt-id"))
    }

    @Test
    fun `empty settings expose stable defaults and persist across instances`() = runTest {
        val storage = InMemoryPreferencesStorage()
        val firstJob = SupervisorJob()
        val firstScope = CoroutineScope(firstJob + Dispatchers.IO)
        val firstStore = DataStoreFactory.create(
            storage = storage,
            scope = firstScope,
            migrations = listOf(SettingsDataMigration()),
        )
        val firstRepository = DataStoreSettingsRepository(firstStore)

        assertEquals(AppSettings(), firstRepository.observeSettings().first())
        firstRepository.saveSettings(
            AppSettings(
                selectedProfileId = ProfileId("profile-1"),
                selectedPromptId = PromptId("prompt-1"),
                batchCount = 3,
                maxConcurrency = 2,
                showPreview = false,
                completionNotifications = false,
                soundNotification = false,
            ),
        )
        assertTrue(firstRepository.observeSettings().first().selectedProfileId == ProfileId("profile-1"))
        firstJob.cancelAndJoin()

        val secondJob = SupervisorJob()
        val secondScope = CoroutineScope(secondJob + Dispatchers.IO)
        val secondStore = DataStoreFactory.create(
            storage = storage,
            scope = secondScope,
            migrations = listOf(SettingsDataMigration()),
        )
        val reloaded = DataStoreSettingsRepository(secondStore).observeSettings().first()

        assertEquals(PromptId("prompt-1"), reloaded.selectedPromptId)
        assertEquals(3, reloaded.batchCount)
        assertEquals(2, reloaded.maxConcurrency)
        assertFalse(reloaded.showPreview)
        assertFalse(reloaded.completionNotifications)
        assertFalse(reloaded.soundNotification)
        secondJob.cancelAndJoin()
    }

    @Test
    fun `migration preserves old values and fills newly introduced defaults`() = runTest {
        val storage = InMemoryPreferencesStorage(
            mutablePreferencesOf(
                SettingsKeys.BATCH_COUNT to 5,
                SettingsKeys.SCHEMA_VERSION to 0,
            ),
        )
        val migratedJob = SupervisorJob()
        val migratedScope = CoroutineScope(migratedJob + Dispatchers.IO)
        val migratedStore = DataStoreFactory.create(
            storage = storage,
            scope = migratedScope,
            migrations = listOf(SettingsDataMigration()),
        )
        val migrated = DataStoreSettingsRepository(migratedStore).observeSettings().first()

        assertEquals(5, migrated.batchCount)
        assertEquals(AppSettings.DEFAULT_MAX_CONCURRENCY, migrated.maxConcurrency)
        assertEquals(AppSettings.DEFAULT_SHOW_PREVIEW, migrated.showPreview)
        assertEquals(
            AppSettings.DEFAULT_COMPLETION_NOTIFICATIONS,
            migrated.completionNotifications,
        )
        assertEquals(AppSettings.DEFAULT_SOUND_NOTIFICATION, migrated.soundNotification)
        migratedJob.cancelAndJoin()
    }

    @Test
    fun `out of range persisted values are clamped instead of terminating the settings flow`() = runTest {
        val storage = InMemoryPreferencesStorage(
            mutablePreferencesOf(
                SettingsKeys.SCHEMA_VERSION to SettingsDataMigration.CURRENT_SCHEMA_VERSION,
                SettingsKeys.BATCH_COUNT to Int.MAX_VALUE,
                SettingsKeys.MAX_CONCURRENCY to 0,
            ),
        )
        val job = SupervisorJob()
        val scope = CoroutineScope(job + Dispatchers.IO)
        val store = DataStoreFactory.create(
            storage = storage,
            scope = scope,
            migrations = listOf(SettingsDataMigration()),
        )

        val settings = DataStoreSettingsRepository(store).observeSettings().first()

        assertEquals(AppSettings.MAX_BATCH_COUNT, settings.batchCount)
        assertEquals(1, settings.maxConcurrency)
        job.cancelAndJoin()
    }
}

private class InMemoryPreferencesStorage(
    initialValue: Preferences = emptyPreferences(),
) : Storage<Preferences> {
    private val mutex = Mutex()
    private val coordinator = TestCoordinator()
    private var value = initialValue

    override fun createConnection(): StorageConnection<Preferences> = object : StorageConnection<Preferences> {
        override val coordinator: InterProcessCoordinator = this@InMemoryPreferencesStorage.coordinator

        override suspend fun <R> readScope(
            block: suspend ReadScope<Preferences>.(Boolean) -> R,
        ): R = mutex.withLock {
            block(TestReadScope(), true)
        }

        override suspend fun writeScope(block: suspend WriteScope<Preferences>.() -> Unit) {
            mutex.withLock {
                block(TestWriteScope())
            }
        }

        override fun close() = Unit
    }

    private open inner class TestReadScope : ReadScope<Preferences> {
        override suspend fun readData(): Preferences = value

        override fun close() = Unit
    }

    private inner class TestWriteScope : TestReadScope(), WriteScope<Preferences> {
        override suspend fun writeData(value: Preferences) {
            this@InMemoryPreferencesStorage.value = value
        }
    }
}

private class TestCoordinator : InterProcessCoordinator {
    private val lock = Mutex()
    private val versionLock = Mutex()
    private val notifications = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    private var version = 0

    override val updateNotifications: Flow<Unit> = notifications

    override suspend fun <T> lock(block: suspend () -> T): T = lock.withLock { block() }

    override suspend fun <T> tryLock(block: suspend (Boolean) -> T): T {
        if (!lock.tryLock()) return block(false)
        return try {
            block(true)
        } finally {
            lock.unlock()
        }
    }

    override suspend fun getVersion(): Int = versionLock.withLock { version }

    override suspend fun incrementAndGetVersion(): Int = versionLock.withLock {
        version += 1
        notifications.tryEmit(Unit)
        version
    }
}
