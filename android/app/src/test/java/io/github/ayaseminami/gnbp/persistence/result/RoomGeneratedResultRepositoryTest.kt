package io.github.ayaseminami.gnbp.persistence.result

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.ayaseminami.gnbp.generation.GeneratedAssetReference
import io.github.ayaseminami.gnbp.generation.GenerationProviderKind
import io.github.ayaseminami.gnbp.generation.GenerationTask
import io.github.ayaseminami.gnbp.generation.TaskId
import io.github.ayaseminami.gnbp.generation.TaskRequestSnapshot
import io.github.ayaseminami.gnbp.generation.TaskStatus
import io.github.ayaseminami.gnbp.persistence.room.GnbpDatabase
import io.github.ayaseminami.gnbp.persistence.task.RoomGenerationTaskRepository
import io.github.ayaseminami.gnbp.provider.GenerationParameters
import io.github.ayaseminami.gnbp.provider.transport.ProfileId
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RoomGeneratedResultRepositoryTest {
    @Test
    fun `committing one successful task repeatedly creates exactly one generated result`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, GnbpDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            val tasks = RoomGenerationTaskRepository(database.taskDao())
            val results = RoomGeneratedResultRepository(database.generatedResultDao())
            val succeeded = successfulTask()

            tasks.insertTasks(listOf(succeeded.copy(status = TaskStatus.Running)))
            tasks.commitSucceededTask(succeeded)
            tasks.commitSucceededTask(succeeded)

            val persisted = results.loadResults().single()
            assertEquals(GeneratedResultId("result-task-one"), persisted.id)
            assertEquals(succeeded.id, persisted.sourceTaskId)
            assertEquals(succeeded.request, persisted.request)
            assertEquals((succeeded.status as TaskStatus.Succeeded).asset, persisted.asset)
            assertEquals(300L, persisted.createdAtEpochMillis)
            assertFalse(persisted.isFavorite)
            assertTrue(tasks.findTask(succeeded.id)?.status is TaskStatus.Succeeded)
            assertEquals(1, results.loadResults().size)
            assertFalse(persisted.toString().contains("private lighthouse prompt"))
            assertFalse(persisted.toString().contains("content://gnbp/generated-one"))
        } finally {
            database.close()
        }
    }

    @Test
    fun `favorite and media URI survive database restart`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val databaseName = "generated-results-${System.nanoTime()}.db"
        val succeeded = successfulTask()
        openDatabase(context, databaseName).let { database ->
            try {
                val tasks = RoomGenerationTaskRepository(database.taskDao())
                val results = RoomGeneratedResultRepository(database.generatedResultDao())
                tasks.insertTasks(listOf(succeeded.copy(status = TaskStatus.Running)))
                tasks.commitSucceededTask(succeeded)
                assertTrue(results.setFavorite(GeneratedResultId.forTask(succeeded.id), true))
            } finally {
                database.close()
            }
        }

        openDatabase(context, databaseName).let { database ->
            try {
                val persisted = RoomGeneratedResultRepository(database.generatedResultDao())
                    .loadResults()
                    .single()
                assertTrue(persisted.isFavorite)
                assertEquals("content://gnbp/generated-one", persisted.asset.location)
            } finally {
                database.close()
            }
        }
        assertTrue(context.deleteDatabase(databaseName))
    }

    @Test
    fun `generated result remains after its source task is removed`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, GnbpDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            val tasks = RoomGenerationTaskRepository(database.taskDao())
            val results = RoomGeneratedResultRepository(database.generatedResultDao())
            val succeeded = successfulTask()
            tasks.insertTasks(listOf(succeeded.copy(status = TaskStatus.Running)))
            tasks.commitSucceededTask(succeeded)

            database.openHelper.writableDatabase.execSQL(
                "DELETE FROM generation_tasks WHERE id = ?",
                arrayOf<Any?>(succeeded.id.value),
            )

            assertEquals(null, tasks.findTask(succeeded.id))
            assertEquals(succeeded.id, results.loadResults().single().sourceTaskId)
        } finally {
            database.close()
        }
    }

    private fun openDatabase(context: Context, name: String): GnbpDatabase =
        Room.databaseBuilder(context, GnbpDatabase::class.java, name)
            .addMigrations(*GnbpDatabase.ALL_MIGRATIONS)
            .allowMainThreadQueries()
            .build()
}

private fun successfulTask() = GenerationTask(
    id = TaskId("task-one"),
    request = TaskRequestSnapshot(
        profileId = ProfileId("profile-one"),
        profileName = "Private profile name",
        providerKind = GenerationProviderKind.Gemini,
        model = "private-model",
        prompt = "private lighthouse prompt",
        parameters = GenerationParameters.Gemini("3:4", "2K", 0.7),
        references = emptyList(),
    ),
    status = TaskStatus.Succeeded(
        GeneratedAssetReference(
            id = "generated-one",
            location = "content://gnbp/generated-one",
            displayName = "generated-one.png",
            mimeType = "image/png",
            byteSize = 3,
        ),
    ),
    createdAtEpochMillis = 100L,
    startedAtEpochMillis = 200L,
    finishedAtEpochMillis = 300L,
)
