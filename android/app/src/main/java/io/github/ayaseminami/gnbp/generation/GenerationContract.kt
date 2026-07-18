package io.github.ayaseminami.gnbp.generation

import io.github.ayaseminami.gnbp.media.AssetRef
import io.github.ayaseminami.gnbp.media.DurableReferenceAsset
import io.github.ayaseminami.gnbp.media.ImagePreparationResult
import io.github.ayaseminami.gnbp.media.MediaAssetId
import io.github.ayaseminami.gnbp.persistence.profile.ProviderKind
import io.github.ayaseminami.gnbp.persistence.profile.ProviderProfile
import io.github.ayaseminami.gnbp.provider.GenerationParameters
import io.github.ayaseminami.gnbp.provider.ImageGenerationProvider
import io.github.ayaseminami.gnbp.provider.transport.ProfileId
import java.io.Closeable
import kotlinx.coroutines.flow.Flow

@JvmInline
value class TaskId(val value: String) {
    init {
        require(value.isNotBlank()) { "Task ID must not be blank" }
        require(value.all { it.isLetterOrDigit() || it == '-' || it == '_' }) {
            "Task ID contains unsupported characters"
        }
    }
}

data class GenerationBatchRequest(
    val profile: ProviderProfile,
    val prompt: String,
    val parameters: GenerationParameters,
    val references: List<DurableReferenceAsset> = emptyList(),
    val count: Int = 1,
)

data class ReferenceAssetSnapshot(
    val id: MediaAssetId,
    val displayName: String,
    val mimeType: String,
) {
    override fun toString(): String =
        "ReferenceAssetSnapshot(id=[REDACTED], displayName=[REDACTED], mimeType=$mimeType)"
}

data class TaskRequestSnapshot(
    val profileId: ProfileId,
    val profileName: String,
    val providerKind: ProviderKind,
    val model: String,
    val prompt: String,
    val parameters: GenerationParameters,
    val references: List<ReferenceAssetSnapshot>,
) {
    override fun toString(): String =
        "TaskRequestSnapshot(profileId=[REDACTED], profileName=[REDACTED], " +
            "providerKind=$providerKind, model=[REDACTED], prompt=[REDACTED], " +
            "parameters=$parameters, references=[REDACTED])"
}

data class GenerationTask(
    val id: TaskId,
    val request: TaskRequestSnapshot,
    val status: TaskStatus,
    val createdAtEpochMillis: Long,
    val startedAtEpochMillis: Long? = null,
    val finishedAtEpochMillis: Long? = null,
    val sourceTaskId: TaskId? = null,
) {
    override fun toString(): String =
        "GenerationTask(id=[REDACTED], request=$request, status=$status, " +
            "createdAt=$createdAtEpochMillis, startedAt=$startedAtEpochMillis, " +
            "finishedAt=$finishedAtEpochMillis, sourceTaskId=[REDACTED])"
}

sealed interface TaskStatus {
    data object Queued : TaskStatus

    data object Running : TaskStatus

    data class Succeeded(val asset: AssetRef) : TaskStatus

    data class Failed(val reason: TaskFailureReason) : TaskStatus

    data class Cancelled(val reason: TaskCancellationReason) : TaskStatus

    data class OutcomeUnknown(val reason: TaskOutcomeUnknownReason) : TaskStatus
}

enum class TaskFailureReason {
    ProviderUnavailable,
    InvalidRequest,
    Blocked,
    HttpStatus,
    Transport,
    MalformedResponse,
    NoImageData,
    AssetSaveFailed,
}

enum class TaskCancellationReason {
    UserRequested,
    ProcessInterruptedBeforeStart,
}

enum class TaskOutcomeUnknownReason {
    ProviderResponseUnknown,
    ProcessInterrupted,
}

sealed interface EnqueueResult {
    data class Accepted(val taskIds: List<TaskId>) : EnqueueResult

    data class Rejected(val reason: EnqueueFailureReason) : EnqueueResult
}

sealed interface EnqueueFailureReason {
    data object BlankPrompt : EnqueueFailureReason

    data object InvalidBatchCount : EnqueueFailureReason

    data object ParameterMismatch : EnqueueFailureReason

    data class ReferencePreparationFailed(val assetId: MediaAssetId) : EnqueueFailureReason
}

sealed interface CancelResult {
    data object Cancelled : CancelResult

    data object CancellationRequested : CancelResult

    data object NotFound : CancelResult

    data object AlreadyFinished : CancelResult
}

sealed interface RetryResult {
    data class Enqueued(val taskId: TaskId) : RetryResult

    data object NotFound : RetryResult

    data object NotRetryable : RetryResult

    data object SnapshotUnavailable : RetryResult
}

interface GenerationEngine : Closeable {
    suspend fun enqueue(request: GenerationBatchRequest): EnqueueResult

    fun observeTasks(): Flow<List<GenerationTask>>

    suspend fun cancel(id: TaskId): CancelResult

    suspend fun retry(id: TaskId): RetryResult
}

fun interface GenerationProviderFactory {
    fun create(profile: ProviderProfile): ImageGenerationProvider
}

fun interface ReferencePreparer {
    suspend fun prepare(asset: DurableReferenceAsset): ImagePreparationResult
}

interface GenerationTaskRepository {
    fun observeTasks(): Flow<List<GenerationTask>>

    suspend fun loadTasks(): List<GenerationTask>

    suspend fun findTask(id: TaskId): GenerationTask?

    suspend fun insertTasks(newTasks: List<GenerationTask>)

    suspend fun updateTask(task: GenerationTask)
}
