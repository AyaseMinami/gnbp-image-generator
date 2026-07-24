package io.github.ayaseminami.gnbp

import android.app.Application
import io.github.ayaseminami.gnbp.background.GenerationRuntime
import io.github.ayaseminami.gnbp.generation.AndroidGenerationProviderFactory
import io.github.ayaseminami.gnbp.generation.DefaultGenerationEngine
import io.github.ayaseminami.gnbp.generation.FileGenerationResultJournal
import io.github.ayaseminami.gnbp.generation.ReferencePreparer
import io.github.ayaseminami.gnbp.media.BoundedImagePreparer
import io.github.ayaseminami.gnbp.media.ContentUriReferenceStore
import io.github.ayaseminami.gnbp.media.ImagePreparationFailure
import io.github.ayaseminami.gnbp.media.ImagePreparationResult
import io.github.ayaseminami.gnbp.media.MediaAssetId
import io.github.ayaseminami.gnbp.media.MediaStoreGeneratedAssetStore
import io.github.ayaseminami.gnbp.media.ReferenceReleaseCleanupReport
import io.github.ayaseminami.gnbp.media.TaskReferenceCleaner
import io.github.ayaseminami.gnbp.persistence.GnbpPersistence
import io.github.ayaseminami.gnbp.persistence.profile.ProfileLoadResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first

class GnbpApplication : Application() {
    internal val graph: GnbpAppGraph by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        GnbpAppGraph(this)
    }
}

internal class GnbpAppGraph(
    private val application: Application,
) {
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val persistence: GnbpPersistence = GnbpPersistence.create(application)
    val referenceStore: ContentUriReferenceStore = ContentUriReferenceStore.create(application)
    val generatedAssetStore: MediaStoreGeneratedAssetStore = MediaStoreGeneratedAssetStore.create(application)
    private val resultJournal = FileGenerationResultJournal.create(application)
    private val taskReferenceCleaner = TaskReferenceCleaner(
        persistence.tasks,
        persistence.generatedResults,
        referenceStore,
    )
    val generationRuntime = GenerationRuntime(engineFactory = ::createEngine)

    private suspend fun createEngine(): DefaultGenerationEngine {
        val settings = persistence.settings.observeSettings().first()
        return DefaultGenerationEngine(
            taskRepository = persistence.tasks,
            completionRepository = persistence.generationCompletion,
            providerFactory = AndroidGenerationProviderFactory(application),
            generatedAssetStore = generatedAssetStore,
            referencePreparer = ReferencePreparer { asset ->
                val durable = referenceStore.resolve(asset)
                    ?: return@ReferencePreparer ImagePreparationResult.Failed(
                        ImagePreparationFailure.SourceMissing,
                    )
                durable.asReferenceImage(BoundedImagePreparer())
            },
            profileLoader = { profileId ->
                when (val loaded = persistence.profiles.loadProfile(profileId)) {
                    is ProfileLoadResult.Found -> loaded.profile
                    else -> null
                }
            },
            maxConcurrency = settings.maxConcurrency,
            externalScope = applicationScope,
            resultJournal = resultJournal,
        )
    }

    suspend fun cleanupReferences(retainedDraftAssetIds: Set<MediaAssetId>) =
        taskReferenceCleaner.cleanupAgedOrphans(
            nowEpochMillis = System.currentTimeMillis(),
            retainedDraftAssetIds = retainedDraftAssetIds,
        )

    suspend fun cleanupReleasedReferences(
        releasedAssetIds: Set<String>,
        retainedDraftAssetIds: Set<MediaAssetId>,
    ): ReferenceReleaseCleanupReport = taskReferenceCleaner.cleanupReleased(
        releasedAssetIds,
        retainedDraftAssetIds,
    )
}

internal val Application.gnbpGraph: GnbpAppGraph
    get() = (this as? GnbpApplication)?.graph
        ?: error("GNBP application graph is unavailable")
