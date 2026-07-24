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

sealed interface DirectReplacementCommit {
    data class Inserted(val taskId: TaskId) : DirectReplacementCommit

    data class Existing(val taskId: TaskId) : DirectReplacementCommit
}

interface GenerationTaskRepository {
    fun observeTasks(): Flow<List<GenerationTask>>

    suspend fun loadTasks(): List<GenerationTask>

    suspend fun findTask(id: TaskId): GenerationTask?

    suspend fun findDirectReplacement(sourceTaskId: TaskId): GenerationTask? =
        loadTasks()
            .filter { task -> task.sourceTaskId == sourceTaskId }
            .minWithOrNull(compareBy(GenerationTask::createdAtEpochMillis, { task -> task.id.value }))

    suspend fun commitDirectReplacement(task: GenerationTask): DirectReplacementCommit

    suspend fun insertTasks(newTasks: List<GenerationTask>)

    suspend fun updateTask(task: GenerationTask)

    suspend fun deleteTerminalTasks(taskIds: Set<TaskId>): Set<TaskId>
}

internal fun interface GenerationCompletionRepository {
    suspend fun commitSucceededTask(task: GenerationTask)
}

internal interface ManagedGenerationEngine : GenerationEngine {
    suspend fun shutdownForInterruption()
}
