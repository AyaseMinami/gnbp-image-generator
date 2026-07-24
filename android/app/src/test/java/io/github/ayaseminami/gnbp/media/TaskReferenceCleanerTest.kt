package io.github.ayaseminami.gnbp.media

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.ayaseminami.gnbp.generation.GeneratedAssetReference
import io.github.ayaseminami.gnbp.generation.GenerationProviderKind
import io.github.ayaseminami.gnbp.generation.GenerationTask
import io.github.ayaseminami.gnbp.generation.ReferenceAssetSnapshot
import io.github.ayaseminami.gnbp.generation.TaskFailureReason
import io.github.ayaseminami.gnbp.generation.TaskId
import io.github.ayaseminami.gnbp.generation.TaskRequestSnapshot
import io.github.ayaseminami.gnbp.generation.TaskStatus
import io.github.ayaseminami.gnbp.persistence.result.RoomGeneratedResultRepository
import io.github.ayaseminami.gnbp.persistence.room.GnbpDatabase
import io.github.ayaseminami.gnbp.persistence.task.RoomGenerationTaskRepository
import io.github.ayaseminami.gnbp.provider.GenerationParameters
import io.github.ayaseminami.gnbp.provider.transport.ProfileId
import java.io.File
import java.io.InputStream
import java.nio.file.Files
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
class TaskReferenceCleanerTest {
    @Test
    fun `cleanup retains references owned by drafts tasks and generated results`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, GnbpDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val root = Files.createTempDirectory("gnbp-task-reference-cleanup-").toFile()
        try {
            val tasks = RoomGenerationTaskRepository(database.taskDao())
            val results = RoomGeneratedResultRepository(database.generatedResultDao())
            val taskOwned = task("task-owner", "task-owned", TaskStatus.Failed(TaskFailureReason.Transport))
            val resultSource = task(
                "result-owner",
                "result-owned",
                TaskStatus.Succeeded(
                    GeneratedAssetReference(
                        id = "generated-result",
                        location = "content://gnbp/generated-result",
                        displayName = "generated-result.png",
                        mimeType = "image/png",
                        byteSize = 3,
                    ),
                ),
            )
            tasks.insertTasks(listOf(taskOwned, resultSource.copy(status = TaskStatus.Running)))
            tasks.commitSucceededTask(resultSource)
            tasks.deleteTerminalTasks(setOf(resultSource.id))

            val ids = listOf("draft-owned", "task-owned", "result-owned", "released-orphan")
            ids.forEach { id -> File(root, "$id.input").writeText(id) }
            val store = ContentUriReferenceStore(
                reader = EmptyContentUriReader,
                rootDirectory = root,
            )
            val cleaner = TaskReferenceCleaner(tasks, results, store)

            val report = cleaner.cleanupReleased(
                releasedAssetIds = ids.toSet(),
                retainedDraftAssetIds = setOf(MediaAssetId("draft-owned")),
            )

            assertEquals(setOf(MediaAssetId("released-orphan")), report.deletedAssetIds)
            assertTrue(report.failedAssetIds.isEmpty())
            assertFalse(File(root, "released-orphan.input").exists())
            assertTrue(File(root, "draft-owned.input").exists())
            assertTrue(File(root, "task-owned.input").exists())
            assertTrue(File(root, "result-owned.input").exists())
        } finally {
            database.close()
            root.deleteRecursively()
        }
    }

    @Test
    fun `aged cleanup retains references still owned by a draft`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, GnbpDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val root = Files.createTempDirectory("gnbp-aged-reference-cleanup-").toFile()
        try {
            val draft = MediaAssetId("draft-owned")
            File(root, "${draft.value}.input").writeText("draft")
            File(root, "orphan.input").writeText("orphan")
            val cleaner = TaskReferenceCleaner(
                tasks = RoomGenerationTaskRepository(database.taskDao()),
                results = RoomGeneratedResultRepository(database.generatedResultDao()),
                referenceStore = ContentUriReferenceStore(EmptyContentUriReader, root),
            )

            val deletedCount = cleaner.cleanupAgedOrphans(
                nowEpochMillis = System.currentTimeMillis() +
                    ReferenceCleanupPolicy.DEFAULT_ORPHAN_RETENTION_MILLIS + 1_000L,
                retainedDraftAssetIds = setOf(draft),
            )

            assertEquals(1, deletedCount)
            assertTrue(File(root, "${draft.value}.input").exists())
            assertFalse(File(root, "orphan.input").exists())
        } finally {
            database.close()
            root.deleteRecursively()
        }
    }
}

private object EmptyContentUriReader : ContentUriReader {
    override fun mimeType(uri: android.net.Uri): String? = null

    override fun displayName(uri: android.net.Uri): String? = null

    override fun open(uri: android.net.Uri): InputStream? = null
}

private fun task(id: String, referenceId: String, status: TaskStatus) = GenerationTask(
    id = TaskId(id),
    request = TaskRequestSnapshot(
        profileId = ProfileId("profile"),
        profileName = "Profile",
        providerKind = GenerationProviderKind.Gemini,
        model = "model",
        prompt = "prompt",
        parameters = GenerationParameters.Gemini("1:1", "1K", 0.9),
        references = listOf(
            ReferenceAssetSnapshot(referenceId, "$referenceId.png", "image/png"),
        ),
    ),
    status = status,
    createdAtEpochMillis = 100L,
    finishedAtEpochMillis = 200L,
)
