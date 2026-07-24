package io.github.ayaseminami.gnbp.ui.gallery

import io.github.ayaseminami.gnbp.generation.GeneratedAssetReference
import io.github.ayaseminami.gnbp.media.AssetAccessResult
import io.github.ayaseminami.gnbp.media.AssetDeleteResult
import io.github.ayaseminami.gnbp.media.AssetRef
import io.github.ayaseminami.gnbp.media.toAssetRef
import io.github.ayaseminami.gnbp.persistence.result.GeneratedResult
import io.github.ayaseminami.gnbp.persistence.result.GeneratedResultId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class GalleryBulkAction {
    Share,
    RemoveFromLibrary,
    DeleteFromDevice,
}

data class GalleryManagementState(
    val isWorking: Boolean = false,
    val feedback: GalleryManagementFeedback? = null,
)

sealed interface GalleryManagementFeedback {
    data class Completed(
        val action: GalleryBulkAction,
        val completedCount: Int,
        val failedCount: Int,
    ) : GalleryManagementFeedback
}

internal class GalleryManagementCoordinator(
    private val scope: CoroutineScope,
    private val findResult: suspend (GeneratedResultId) -> GeneratedResult?,
    private val removeResults: suspend (Set<GeneratedResultId>) -> Set<GeneratedResultId>,
    private val checkAsset: suspend (AssetRef) -> AssetAccessResult,
    private val deleteAsset: suspend (AssetRef) -> AssetDeleteResult,
    private val shareReady: suspend (List<GeneratedAssetReference>) -> Unit,
) {
    private val mutableState = MutableStateFlow(GalleryManagementState())
    val state: StateFlow<GalleryManagementState> = mutableState.asStateFlow()

    fun removeFromLibrary(ids: Set<GeneratedResultId>) = runCommand(
        action = GalleryBulkAction.RemoveFromLibrary,
        requestedIds = ids,
    ) {
        removeResults(ids)
    }

    fun shareResults(ids: Set<GeneratedResultId>) = runCommand(
        action = GalleryBulkAction.Share,
        requestedIds = ids,
    ) {
        val shareableIds = mutableSetOf<GeneratedResultId>()
        val shareableAssets = mutableListOf<GeneratedAssetReference>()
        ids.forEach { id ->
            try {
                val result = findResult(id) ?: return@forEach
                if (checkAsset(result.asset.toAssetRef()) == AssetAccessResult.Available) {
                    shareableIds += id
                    shareableAssets += result.asset
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                Unit
            }
        }
        if (shareableAssets.isNotEmpty()) shareReady(shareableAssets)
        shareableIds
    }

    fun deleteFromDevice(ids: Set<GeneratedResultId>) = runCommand(
        action = GalleryBulkAction.DeleteFromDevice,
        requestedIds = ids,
    ) {
        val mediaDeletedIds = ids.mapNotNullTo(mutableSetOf()) { id ->
            try {
                val result = findResult(id) ?: return@mapNotNullTo null
                id.takeIf { deleteAsset(result.asset.toAssetRef()) == AssetDeleteResult.Deleted }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                null
            }
        }
        if (mediaDeletedIds.isEmpty()) emptySet() else removeResults(mediaDeletedIds)
    }

    fun clearFeedback() {
        mutableState.update { state -> state.copy(feedback = null) }
    }

    private fun runCommand(
        action: GalleryBulkAction,
        requestedIds: Set<GeneratedResultId>,
        block: suspend () -> Set<GeneratedResultId>,
    ) {
        if (requestedIds.isEmpty()) return
        val current = mutableState.value
        if (current.isWorking) return
        if (!mutableState.compareAndSet(current, current.copy(isWorking = true, feedback = null))) return
        scope.launch {
            try {
                val completedIds = try {
                    block()
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    emptySet()
                }
                mutableState.update { state ->
                    state.copy(
                        feedback = GalleryManagementFeedback.Completed(
                            action = action,
                            completedCount = completedIds.size,
                            failedCount = requestedIds.size - completedIds.size,
                        ),
                    )
                }
            } finally {
                mutableState.update { state -> state.copy(isWorking = false) }
            }
        }
    }
}
