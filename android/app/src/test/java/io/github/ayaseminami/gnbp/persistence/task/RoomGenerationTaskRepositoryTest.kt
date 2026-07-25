package io.github.ayaseminami.gnbp.persistence.task

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.ayaseminami.gnbp.generation.DirectReplacementCommit
import io.github.ayaseminami.gnbp.generation.GeneratedAssetReference
import io.github.ayaseminami.gnbp.generation.GenerationProviderKind
import io.github.ayaseminami.gnbp.generation.GenerationTask
import io.github.ayaseminami.gnbp.generation.ReferenceAssetSnapshot
import io.github.ayaseminami.gnbp.generation.TaskCancellationReason
import io.github.ayaseminami.gnbp.generation.TaskFailureDiagnostic
import io.github.ayaseminami.gnbp.generation.TaskFailureReason
import io.github.ayaseminami.gnbp.generation.TaskId
import io.github.ayaseminami.gnbp.generation.TaskOutcomeUnknownReason
import io.github.ayaseminami.gnbp.generation.TaskRequestSnapshot
import io.github.ayaseminami.gnbp.generation.TaskStatus
import io.github.ayaseminami.gnbp.persistence.room.GnbpDatabase
import io.github.ayaseminami.gnbp.provider.GenerationParameters
import io.github.ayaseminami.gnbp.provider.transport.ProfileId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
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
    fun `safe HTTP failure diagnostic survives a database restart`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val databaseName = "gnbp-diagnostic-restart-${System.nanoTime()}.db"
        val diagnostic = TaskFailureDiagnostic(
            httpStatusCode = 524,
            providerMessage = "Upstream request timed out",
        )
        val failed = generationTask(
            "http-failed",
            TaskStatus.Failed(TaskFailureReason.HttpStatus, diagnostic),
        )

        openDatabase(context, databaseName).let { database ->
            try {
                RoomGenerationTaskRepository(database.taskDao()).insertTasks(listOf(failed))
            } finally {
                database.close()
            }
        }

        openDatabase(context, databaseName).let { database ->
            try {
                assertEquals(
                    failed,
                    RoomGenerationTaskRepository(database.taskDao()).findTask(failed.id),
                )
                val entityText = database.taskDao().findById(failed.id.value).toString()
                assertFalse(entityText.contains("Upstream request timed out"))
                assertTrue(entityText.contains("failureDiagnostic=[REDACTED]"))
            } finally {
                database.close()
            }
        }
        assertTrue(context.deleteDatabase(databaseName))
    }

    @Test
    fun `invalid persisted failure diagnostic degrades without hiding its task`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, GnbpDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            val repository = RoomGenerationTaskRepository(database.taskDao())
            val failed = generationTask(
                "invalid-diagnostic",
                TaskStatus.Failed(
                    TaskFailureReason.HttpStatus,
                    TaskFailureDiagnostic(524, "Upstream request timed out"),
                ),
            )
            repository.insertTasks(listOf(failed))
            database.openHelper.writableDatabase.execSQL(
                "UPDATE generation_tasks " +
                    "SET failure_http_status = 999, failure_provider_message = ? WHERE id = ?",
                arrayOf("unsafe\nmessage", failed.id.value),
            )

            assertEquals(
                failed.copy(status = TaskStatus.Failed(TaskFailureReason.HttpStatus)),
                repository.findTask(failed.id),
            )
        } finally {
            database.close()
        }
    }

    @Test
    fun `task summaries and terminal results survive a database restart`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val databaseName = "gnbp-task-restart-${System.nanoTime()}.db"
        val queued = generationTask("queued", TaskStatus.Queued)
        val deleted = generationTask(
            "deleted-failed",
            TaskStatus.Failed(TaskFailureReason.Transport),
        )
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
                RoomGenerationTaskRepository(database.taskDao()).let { repository ->
                    repository.insertTasks(listOf(queued, succeeded, deleted))
                    assertEquals(
                        setOf(deleted.id),
                        repository.deleteTerminalTasks(setOf(deleted.id)),
                    )
                }
            } finally {
                database.close()
            }
        }

        openDatabase(context, databaseName).let { database ->
            try {
                val repository = RoomGenerationTaskRepository(database.taskDao())
                assertEquals(queued, repository.findTask(TaskId("queued")))
                assertEquals(succeeded, repository.findTask(TaskId("succeeded")))
                assertEquals(null, repository.findTask(deleted.id))
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

    @Test
    fun `direct replacement lookup survives a database restart`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val databaseName = "gnbp-retry-restart-${System.nanoTime()}.db"
        val source = generationTask(
            id = "failed-source",
            status = TaskStatus.Failed(TaskFailureReason.Transport),
        )
        val replacement = generationTask("replacement", TaskStatus.Queued).copy(
            createdAtEpochMillis = 2_000L,
            sourceTaskId = source.id,
        )

        openDatabase(context, databaseName).let { database ->
            try {
                RoomGenerationTaskRepository(database.taskDao())
                    .insertTasks(listOf(source, replacement))
            } finally {
                database.close()
            }
        }

        openDatabase(context, databaseName).let { database ->
            try {
                val repository = RoomGenerationTaskRepository(database.taskDao())
                assertEquals(replacement, repository.findDirectReplacement(source.id))
            } finally {
                database.close()
            }
        }
        assertTrue(context.deleteDatabase(databaseName))
    }

    @Test
    fun `database rejects a second direct replacement for one source task`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, GnbpDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            val repository = RoomGenerationTaskRepository(database.taskDao())
            val source = generationTask(
                id = "failed-source",
                status = TaskStatus.Failed(TaskFailureReason.Transport),
            )
            val firstReplacement = generationTask("replacement-one", TaskStatus.Queued).copy(
                sourceTaskId = source.id,
            )
            val secondReplacement = generationTask("replacement-two", TaskStatus.Queued).copy(
                sourceTaskId = source.id,
            )
            repository.insertTasks(listOf(source, firstReplacement))

            repository.insertTasks(listOf(secondReplacement))

            assertEquals(
                1,
                repository.loadTasks().count { task -> task.sourceTaskId == source.id },
            )
            assertEquals(
                firstReplacement,
                repository.findDirectReplacement(source.id),
            )
        } finally {
            database.close()
        }
    }

    @Test
    fun `concurrent direct replacement commits return one persisted task`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, GnbpDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            val repository = RoomGenerationTaskRepository(database.taskDao())
            val source = generationTask(
                id = "failed-source",
                status = TaskStatus.Failed(TaskFailureReason.Transport),
            )
            repository.insertTasks(listOf(source))
            val candidates = listOf("replacement-one", "replacement-two").map { id ->
                generationTask(id, TaskStatus.Queued).copy(sourceTaskId = source.id)
            }

            val commits = coroutineScope {
                candidates.map { candidate ->
                    async(Dispatchers.IO) {
                        repository.commitDirectReplacement(candidate)
                    }
                }.awaitAll()
            }

            assertEquals(1, commits.count { it is DirectReplacementCommit.Inserted })
            assertEquals(1, commits.count { it is DirectReplacementCommit.Existing })
            assertEquals(
                1,
                commits.map { commit ->
                    when (commit) {
                        is DirectReplacementCommit.Inserted -> commit.taskId
                        is DirectReplacementCommit.Existing -> commit.taskId
                    }
                }.distinct().size,
            )
            assertEquals(
                1,
                repository.loadTasks().count { task -> task.sourceTaskId == source.id },
            )
        } finally {
            database.close()
        }
    }

    @Test
    fun `bulk terminal deletion retains active and unknown tasks`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, GnbpDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val deletable = listOf(
            generationTask(
                "succeeded",
                TaskStatus.Succeeded(
                    GeneratedAssetReference(
                        id = "generated",
                        location = "content://gnbp/generated",
                        displayName = "generated.png",
                        mimeType = "image/png",
                        byteSize = 3,
                    ),
                ),
            ),
            generationTask("failed", TaskStatus.Failed(TaskFailureReason.Transport)),
            generationTask(
                "cancelled",
                TaskStatus.Cancelled(TaskCancellationReason.UserRequested),
            ),
        )
        val retained = listOf(
            generationTask("queued", TaskStatus.Queued),
            generationTask("running", TaskStatus.Running),
            generationTask(
                "unknown",
                TaskStatus.OutcomeUnknown(TaskOutcomeUnknownReason.ProviderResponseUnknown),
            ),
        )
        try {
            val repository = RoomGenerationTaskRepository(database.taskDao())
            repository.insertTasks(deletable + retained)
            assertEquals(
                deletable.mapTo(mutableSetOf(), GenerationTask::id),
                repository.deleteTerminalTasks(
                    (deletable + retained).mapTo(mutableSetOf(), GenerationTask::id),
                ),
            )
            assertEquals(
                retained.mapTo(mutableSetOf(), GenerationTask::id),
                repository.loadTasks().mapTo(mutableSetOf(), GenerationTask::id),
            )
        } finally {
            database.close()
        }
    }

    @Test
    fun `bulk terminal deletion retains replacement when its retryable source is retained`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, GnbpDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            val repository = RoomGenerationTaskRepository(database.taskDao())
            val source = generationTask(
                "unknown-source",
                TaskStatus.OutcomeUnknown(TaskOutcomeUnknownReason.ProviderResponseUnknown),
            )
            val replacement = generationTask(
                "failed-replacement",
                TaskStatus.Failed(TaskFailureReason.Transport),
            ).copy(sourceTaskId = source.id)
            repository.insertTasks(listOf(source, replacement))

            assertEquals(
                emptySet<TaskId>(),
                repository.deleteTerminalTasks(setOf(source.id, replacement.id)),
            )
            assertEquals(
                setOf(source.id, replacement.id),
                repository.loadTasks().mapTo(mutableSetOf(), GenerationTask::id),
            )
        } finally {
            database.close()
        }
    }

    @Test
    fun `bulk terminal deletion removes replacement when its retained source is not retryable`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, GnbpDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            val repository = RoomGenerationTaskRepository(database.taskDao())
            val source = generationTask(
                "succeeded-source",
                TaskStatus.Succeeded(
                    GeneratedAssetReference(
                        id = "generated-source",
                        location = "content://gnbp/generated-source",
                        displayName = "generated-source.png",
                        mimeType = "image/png",
                        byteSize = 3,
                    ),
                ),
            )
            val replacement = generationTask(
                "failed-replacement",
                TaskStatus.Failed(TaskFailureReason.Transport),
            ).copy(sourceTaskId = source.id)
            repository.insertTasks(listOf(source, replacement))

            assertEquals(
                setOf(replacement.id),
                repository.deleteTerminalTasks(setOf(replacement.id)),
            )
            assertEquals(listOf(source), repository.loadTasks())
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
