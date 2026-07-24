package io.github.ayaseminami.gnbp.ui.generation

import io.github.ayaseminami.gnbp.generation.TaskDeletionReport
import io.github.ayaseminami.gnbp.generation.TaskId
import io.github.ayaseminami.gnbp.media.MediaAssetId
import io.github.ayaseminami.gnbp.media.ReferenceReleaseCleanupReport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class TaskManagementState(
    val isDeleting: Boolean = false,
    val feedback: TaskManagementFeedback? = null,
)

sealed interface TaskManagementFeedback {
    data class DeletionCompleted(
        val deletedCount: Int,
        val blockedCount: Int,
        val cleanupFailedCount: Int,
    ) : TaskManagementFeedback

    data object DeletionFailed : TaskManagementFeedback
}

internal class TaskManagementCoordinator(
    private val scope: CoroutineScope,
    private val deleteTasks: suspend (Set<TaskId>) -> TaskDeletionReport,
    private val cleanupReleasedReferences: suspend (
        Set<String>,
        Set<MediaAssetId>,
    ) -> ReferenceReleaseCleanupReport,
) {
    private val mutableState = MutableStateFlow(TaskManagementState())
    val state: StateFlow<TaskManagementState> = mutableState.asStateFlow()

    fun deleteTasks(
        taskIds: Set<TaskId>,
        retainedDraftAssetIds: Set<MediaAssetId>,
    ) {
        if (taskIds.isEmpty()) return
        val current = mutableState.value
        if (current.isDeleting) return
        if (!mutableState.compareAndSet(current, current.copy(isDeleting = true, feedback = null))) return
        scope.launch {
            try {
                val report = deleteTasks(taskIds)
                val cleanup = if (report.releasedReferenceAssetIds.isEmpty()) {
                    ReferenceReleaseCleanupReport(emptySet(), emptySet())
                } else {
                    try {
                        cleanupReleasedReferences(
                            report.releasedReferenceAssetIds,
                            retainedDraftAssetIds,
                        )
                    } catch (error: CancellationException) {
                        throw error
                    } catch (_: Exception) {
                        ReferenceReleaseCleanupReport(
                            deletedAssetIds = emptySet(),
                            failedAssetIds = report.releasedReferenceAssetIds.mapNotNullTo(
                                mutableSetOf(),
                            ) { rawId -> runCatching { MediaAssetId(rawId) }.getOrNull() },
                        )
                    }
                }
                mutableState.update { state ->
                    state.copy(
                        feedback = TaskManagementFeedback.DeletionCompleted(
                            deletedCount = report.deletedTaskIds.size,
                            blockedCount = report.blockedTaskIds.size,
                            cleanupFailedCount = cleanup.failedAssetIds.size,
                        ),
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                mutableState.update { state ->
                    state.copy(feedback = TaskManagementFeedback.DeletionFailed)
                }
            } finally {
                mutableState.update { state -> state.copy(isDeleting = false) }
            }
        }
    }

    fun clearFeedback() {
        mutableState.update { state -> state.copy(feedback = null) }
    }
}
