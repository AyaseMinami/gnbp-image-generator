package io.github.ayaseminami.gnbp.generation

import io.github.ayaseminami.gnbp.provider.GenerationParameters
import io.github.ayaseminami.gnbp.provider.MAX_PROVIDER_MESSAGE_LENGTH
import io.github.ayaseminami.gnbp.provider.isUnsafeProviderMessageCharacter
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

enum class GenerationProviderKind {
    Gemini,
    OpenAiCompatible,
}

data class ReferenceAssetInput(
    val id: String,
    val displayName: String,
    val mimeType: String,
) {
    init {
        require(id.isNotBlank()) { "Reference asset ID must not be blank" }
        require(displayName.isNotBlank()) { "Reference display name must not be blank" }
        require(mimeType.startsWith("image/")) { "Reference MIME type must be an image" }
    }

    override fun toString(): String =
        "ReferenceAssetInput(id=[REDACTED], displayName=[REDACTED], mimeType=$mimeType)"
}

data class GeneratedAssetReference(
    val id: String,
    val location: String,
    val displayName: String,
    val mimeType: String,
    val byteSize: Long,
) {
    init {
        require(id.isNotBlank()) { "Generated asset ID must not be blank" }
        require(location.isNotBlank()) { "Generated asset location must not be blank" }
        require(displayName.isNotBlank()) { "Generated display name must not be blank" }
        require(mimeType.startsWith("image/")) { "Generated MIME type must be an image" }
        require(byteSize > 0) { "Generated asset byte size must be positive" }
    }

    override fun toString(): String =
        "GeneratedAssetReference(id=[REDACTED], location=[REDACTED], " +
            "displayName=[REDACTED], mimeType=$mimeType, byteSize=$byteSize)"
}

data class GenerationBatchRequest(
    val profileId: ProfileId,
    val prompt: String,
    val parameters: GenerationParameters,
    val references: List<ReferenceAssetInput> = emptyList(),
    val count: Int = 1,
)

data class ReferenceAssetSnapshot(
    val id: String,
    val displayName: String,
    val mimeType: String,
) {
    override fun toString(): String =
        "ReferenceAssetSnapshot(id=[REDACTED], displayName=[REDACTED], mimeType=$mimeType)"
}

data class TaskRequestSnapshot(
    val profileId: ProfileId,
    val profileName: String,
    val providerKind: GenerationProviderKind,
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

    data class Succeeded(val asset: GeneratedAssetReference) : TaskStatus

    data class Failed(
        val reason: TaskFailureReason,
        val diagnostic: TaskFailureDiagnostic? = null,
    ) : TaskStatus

    data class Cancelled(val reason: TaskCancellationReason) : TaskStatus

    data class OutcomeUnknown(val reason: TaskOutcomeUnknownReason) : TaskStatus
}

data class TaskFailureDiagnostic(
    val httpStatusCode: Int? = null,
    val providerMessage: String? = null,
) {
    init {
        require(httpStatusCode != null || providerMessage != null) {
            "A failure diagnostic must contain a status code or provider message"
        }
        require(httpStatusCode == null || isSupportedHttpStatusCode(httpStatusCode)) {
            "HTTP status code is outside the supported range"
        }
        providerMessage?.let { message ->
            require(isSafeProviderMessage(message)) {
                "Provider message is outside the safe display policy"
            }
        }
    }

    override fun toString(): String =
        "TaskFailureDiagnostic(httpStatusCode=$httpStatusCode, providerMessage=[REDACTED])"

    companion object {
        internal fun fromUntrusted(
            httpStatusCode: Int?,
            providerMessage: String?,
        ): TaskFailureDiagnostic? {
            val safeStatusCode = httpStatusCode?.takeIf(::isSupportedHttpStatusCode)
            val safeProviderMessage = providerMessage?.takeIf(::isSafeProviderMessage)
            if (safeStatusCode == null && safeProviderMessage == null) return null
            return TaskFailureDiagnostic(safeStatusCode, safeProviderMessage)
        }

        private fun isSupportedHttpStatusCode(value: Int): Boolean = value in 100..599

        private fun isSafeProviderMessage(value: String): Boolean =
            value.isNotBlank() &&
                value.length <= MAX_PROVIDER_MESSAGE_LENGTH &&
                value.none(Char::isUnsafeProviderMessageCharacter)
    }
}

enum class TaskFailureReason {
    ProviderUnavailable,
    InvalidRequest,
    Blocked,
    HttpStatus,
    Transport,
    MalformedResponse,
    NoImageData,
    ReferenceUnavailable,
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
    data object ProfileUnavailable : EnqueueFailureReason

    data object BlankPrompt : EnqueueFailureReason

    data object InvalidBatchCount : EnqueueFailureReason

    data object ParameterMismatch : EnqueueFailureReason

    data class ReferencePreparationFailed(val assetId: String) : EnqueueFailureReason
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

enum class TaskDeletionBlockReason {
    NotFound,
    Active,
    OutcomeUnknown,
    RetryLineage,
    ResultReconciliationPending,
    LocalCleanupFailed,
}

data class TaskDeletionReport(
    val deletedTaskIds: Set<TaskId>,
    val blockedTaskIds: Map<TaskId, TaskDeletionBlockReason>,
    val releasedReferenceAssetIds: Set<String>,
) {
    override fun toString(): String =
        "TaskDeletionReport(deletedTaskIds=[REDACTED], blockedTaskIds=[REDACTED], " +
            "releasedReferenceAssetIds=[REDACTED])"
}

interface GenerationEngine : Closeable {
    suspend fun enqueue(request: GenerationBatchRequest): EnqueueResult

    fun observeTasks(): Flow<List<GenerationTask>>

    suspend fun cancel(id: TaskId): CancelResult

    suspend fun retry(id: TaskId): RetryResult

    suspend fun deleteTasks(taskIds: Set<TaskId>): TaskDeletionReport
}
