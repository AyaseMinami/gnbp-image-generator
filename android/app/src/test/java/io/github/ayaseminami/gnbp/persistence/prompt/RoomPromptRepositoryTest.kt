package io.github.ayaseminami.gnbp.persistence.prompt

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.ayaseminami.gnbp.persistence.room.GnbpDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RoomPromptRepositoryTest {
    private lateinit var database: GnbpDatabase
    private lateinit var repository: PromptRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, GnbpDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = RoomPromptRepository(database.promptDao())
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `prompt CRUD remains ordered and observable`() = runTest {
        val later = PromptPreset(PromptId("later"), "Later", "second prompt", sortOrder = 2)
        val earlier = PromptPreset(PromptId("earlier"), "Earlier", "first prompt", sortOrder = 1)

        repository.savePrompt(later)
        repository.savePrompt(earlier)

        assertEquals(listOf(earlier, later), repository.observePrompts().first())
        assertEquals(earlier, repository.loadPrompt(earlier.id))

        val updated = PromptPreset(earlier.id, "Updated", "updated prompt", sortOrder = 3)
        repository.savePrompt(updated)
        assertEquals(listOf(later, updated), repository.observePrompts().first())

        assertTrue(repository.deletePrompt(later.id))
        assertEquals(listOf(updated), repository.observePrompts().first())
        assertEquals(null, repository.loadPrompt(later.id))
    }
}
