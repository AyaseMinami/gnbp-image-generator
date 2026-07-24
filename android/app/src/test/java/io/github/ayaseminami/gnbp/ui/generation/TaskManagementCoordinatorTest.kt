package io.github.ayaseminami.gnbp.ui.generation

import io.github.ayaseminami.gnbp.generation.TaskDeletionBlockReason
import io.github.ayaseminami.gnbp.generation.TaskDeletionReport
import io.github.ayaseminami.gnbp.generation.TaskId
import io.github.ayaseminami.gnbp.media.MediaAssetId
import io.github.ayaseminami.gnbp.media.ReferenceReleaseCleanupReport
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TaskManagementCoordinatorTest {
    @Test
    fun `deletion reports removed blocked and private cleanup counts`() = runTest {
        val cleanupCalls = mutableListOf<Pair<Set<String>, Set<MediaAssetId>>>()
        val coordinator = TaskManagementCoordinator(
            scope = this,
            deleteTasks = {
                TaskDeletionReport(
                    deletedTaskIds = setOf(TaskId("deleted-one"), TaskId("deleted-two")),
                    blockedTaskIds = mapOf(TaskId("unknown") to TaskDeletionBlockReason.OutcomeUnknown),
                    releasedReferenceAssetIds = setOf("released-one", "released-two"),
                )
            },
            cleanupReleasedReferences = { released, drafts ->
                cleanupCalls += released to drafts
                ReferenceReleaseCleanupReport(
                    deletedAssetIds = setOf(MediaAssetId("released-one")),
                    failedAssetIds = setOf(MediaAssetId("released-two")),
                )
            },
        )

        coordinator.deleteTasks(
            taskIds = setOf(TaskId("deleted-one"), TaskId("deleted-two"), TaskId("unknown")),
            retainedDraftAssetIds = setOf(MediaAssetId("draft-one")),
        )
        advanceUntilIdle()

        assertEquals(
            listOf(
                setOf("released-one", "released-two") to setOf(MediaAssetId("draft-one")),
            ),
            cleanupCalls,
        )
        assertEquals(
            TaskManagementFeedback.DeletionCompleted(
                deletedCount = 2,
                blockedCount = 1,
                cleanupFailedCount = 1,
            ),
            coordinator.state.value.feedback,
        )
        assertFalse(coordinator.state.value.isDeleting)
    }

    @Test
    fun `a second deletion is ignored while the first command is active`() = runTest {
        val allowDeletion = CompletableDeferred<Unit>()
        var commandCount = 0
        val coordinator = TaskManagementCoordinator(
            scope = this,
            deleteTasks = {
                commandCount += 1
                allowDeletion.await()
                TaskDeletionReport(emptySet(), emptyMap(), emptySet())
            },
            cleanupReleasedReferences = { _, _ ->
                ReferenceReleaseCleanupReport(emptySet(), emptySet())
            },
        )

        coordinator.deleteTasks(setOf(TaskId("first")), emptySet())
        runCurrent()
        assertTrue(coordinator.state.value.isDeleting)
        coordinator.deleteTasks(setOf(TaskId("second")), emptySet())
        runCurrent()
        assertEquals(1, commandCount)

        allowDeletion.complete(Unit)
        advanceUntilIdle()
        assertFalse(coordinator.state.value.isDeleting)
    }

    @Test
    fun `cleanup exception preserves deletion outcome and reports unreclaimed references`() = runTest {
        val coordinator = TaskManagementCoordinator(
            scope = this,
            deleteTasks = {
                TaskDeletionReport(
                    deletedTaskIds = setOf(TaskId("deleted")),
                    blockedTaskIds = emptyMap(),
                    releasedReferenceAssetIds = setOf("released"),
                )
            },
            cleanupReleasedReferences = { _, _ -> error("storage unavailable") },
        )

        coordinator.deleteTasks(setOf(TaskId("deleted")), emptySet())
        advanceUntilIdle()

        assertEquals(
            TaskManagementFeedback.DeletionCompleted(
                deletedCount = 1,
                blockedCount = 0,
                cleanupFailedCount = 1,
            ),
            coordinator.state.value.feedback,
        )
        assertFalse(coordinator.state.value.isDeleting)
    }
}
