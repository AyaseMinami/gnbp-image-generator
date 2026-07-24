package io.github.ayaseminami.gnbp.media

import io.github.ayaseminami.gnbp.generation.GenerationTaskRepository
import io.github.ayaseminami.gnbp.persistence.result.GeneratedResultRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal class TaskReferenceCleaner(
    private val tasks: GenerationTaskRepository,
    private val results: GeneratedResultRepository,
    private val referenceStore: ContentUriReferenceStore,
) {
    suspend fun cleanupReleased(
        releasedAssetIds: Set<String>,
        retainedDraftAssetIds: Set<MediaAssetId>,
    ): ReferenceReleaseCleanupReport {
        val released = releasedAssetIds.mapNotNullTo(mutableSetOf()) { rawId ->
            runCatching { MediaAssetId(rawId) }.getOrNull()
        }
        return referenceStore.cleanupReleasedCopies(
            releasedAssetIds = released,
            retainedAssetIds = retainedAssetIds(retainedDraftAssetIds),
        )
    }

    suspend fun cleanupAgedOrphans(
        nowEpochMillis: Long,
        retainedDraftAssetIds: Set<MediaAssetId>,
    ): Int {
        val retained = retainedAssetIds(retainedDraftAssetIds)
        return withContext(Dispatchers.IO) {
            referenceStore.cleanupOrphanedCopies(retained, nowEpochMillis)
        }
    }

    private suspend fun retainedAssetIds(draftAssetIds: Set<MediaAssetId>): Set<MediaAssetId> =
        buildSet {
            addAll(draftAssetIds)
            tasks.loadTasks().forEach { task ->
                task.request.references.mapTo(this) { reference -> MediaAssetId(reference.id) }
            }
            results.loadResults().forEach { result ->
                result.request.references.mapTo(this) { reference -> MediaAssetId(reference.id) }
            }
        }
}
