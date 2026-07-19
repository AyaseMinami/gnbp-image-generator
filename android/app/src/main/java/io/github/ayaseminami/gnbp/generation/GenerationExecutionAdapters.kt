package io.github.ayaseminami.gnbp.generation

import io.github.ayaseminami.gnbp.media.ImagePreparationResult
import io.github.ayaseminami.gnbp.persistence.profile.ProviderProfile
import io.github.ayaseminami.gnbp.provider.ImageGenerationProvider
import kotlinx.coroutines.flow.Flow

internal fun interface GenerationProviderFactory {
    fun create(profile: ProviderProfile): ImageGenerationProvider
}

internal fun interface ReferencePreparer {
    suspend fun prepare(asset: ReferenceAssetInput): ImagePreparationResult
}

interface GenerationTaskRepository {
    fun observeTasks(): Flow<List<GenerationTask>>

    suspend fun loadTasks(): List<GenerationTask>

    suspend fun findTask(id: TaskId): GenerationTask?

    suspend fun insertTasks(newTasks: List<GenerationTask>)

    suspend fun updateTask(task: GenerationTask)
}

internal interface ManagedGenerationEngine : GenerationEngine {
    suspend fun shutdownForInterruption()
}
