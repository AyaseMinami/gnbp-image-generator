package io.github.ayaseminami.gnbp.persistence.task

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.ayaseminami.gnbp.generation.GenerationTask
import io.github.ayaseminami.gnbp.generation.GeneratedAssetReference
import io.github.ayaseminami.gnbp.generation.GenerationProviderKind
import io.github.ayaseminami.gnbp.generation.ReferenceAssetSnapshot
import io.github.ayaseminami.gnbp.generation.TaskFailureReason
import io.github.ayaseminami.gnbp.generation.TaskId
import io.github.ayaseminami.gnbp.generation.TaskRequestSnapshot
import io.github.ayaseminami.gnbp.generation.TaskStatus
import io.github.ayaseminami.gnbp.persistence.room.GnbpDatabase
import io.github.ayaseminami.gnbp.provider.GenerationParameters
import io.github.ayaseminami.gnbp.provider.transport.ProfileId
import kotlinx.coroutines.flow.first
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
class RoomGenerationTaskRepositoryTest {
    @Test
    fun `task summaries and terminal results survive a database restart`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val databaseName = "gnbp-task-restart-${System.nanoTime()}.db"
        val queued = generationTask("queued", TaskStatus.Queued)
        val succeeded = generationTask(
            "succeeded",
            TaskStatus.Succeeded(
                GeneratedAssetReference(
                    id = "result-one",
                    location = "content://media/result-one",
                    displayName = "result-one.png",
                    mimeType = "image/png",
                    byteSize = 3,
                ),
            ),
        ).copy(startedAtEpochMillis = 1_100L, finishedAtEpochMillis = 1_200L)

        openDatabase(context, databaseName).let { database ->
            try {
                RoomGenerationTaskRepository(database.taskDao()).insertTasks(listOf(queued, succeeded))
            } finally {
                database.close()
            }
        }

        openDatabase(context, databaseName).let { database ->
            try {
                val repository = RoomGenerationTaskRepository(database.taskDao())
                assertEquals(queued, repository.findTask(TaskId("queued")))
                assertEquals(succeeded, repository.findTask(TaskId("succeeded")))
                assertEquals(listOf(succeeded, queued), repository.observeTasks().first())

                val queuedEntityText = database.taskDao().findById("queued").toString()
                val succeededEntityText = database.taskDao().findById("succeeded").toString()
                assertFalse(queuedEntityText.contains("private prompt"))
                assertFalse(queuedEntityText.contains("reference-private.jpg"))
                assertFalse(succeededEntityText.contains("content://media/result-one"))
            } finally {
                database.close()
            }
        }
        assertTrue(context.deleteDatabase(databaseName))
    }

    @Test
    fun `updating a task replaces its state without changing its request snapshot`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, GnbpDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            val repository = RoomGenerationTaskRepository(database.taskDao())
            val queued = generationTask("task-one", TaskStatus.Queued)
            repository.insertTasks(listOf(queued))

            repository.updateTask(
                queued.copy(
                    status = TaskStatus.Failed(TaskFailureReason.HttpStatus),
                    startedAtEpochMillis = 2_000L,
                    finishedAtEpochMillis = 3_000L,
                ),
            )

            val updated = repository.findTask(queued.id)
            assertEquals(queued.request, updated?.request)
            assertEquals(TaskStatus.Failed(TaskFailureReason.HttpStatus), updated?.status)
            assertEquals(2_000L, updated?.startedAtEpochMillis)
            assertEquals(3_000L, updated?.finishedAtEpochMillis)
        } finally {
            database.close()
        }
    }

    private fun openDatabase(context: Context, name: String): GnbpDatabase =
        Room.databaseBuilder(context, GnbpDatabase::class.java, name)
            .addMigrations(GnbpDatabase.MIGRATION_1_2, GnbpDatabase.MIGRATION_2_3)
            .allowMainThreadQueries()
            .build()
}

private fun generationTask(id: String, status: TaskStatus) = GenerationTask(
    id = TaskId(id),
    request = TaskRequestSnapshot(
        profileId = ProfileId("profile-one"),
        profileName = "Profile One",
        providerKind = GenerationProviderKind.Gemini,
        model = "gemini-test",
        prompt = "private prompt",
        parameters = GenerationParameters.Gemini("3:4", "2K", 0.7),
        references = listOf(
            ReferenceAssetSnapshot(
                id = "reference-one",
                displayName = "reference-private.jpg",
                mimeType = "image/jpeg",
            ),
        ),
    ),
    status = status,
    createdAtEpochMillis = 1_000L,
)
