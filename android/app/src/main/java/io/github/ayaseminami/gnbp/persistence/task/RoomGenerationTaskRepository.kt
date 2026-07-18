package io.github.ayaseminami.gnbp.persistence.task

import androidx.core.net.toUri
import io.github.ayaseminami.gnbp.generation.GenerationTask
import io.github.ayaseminami.gnbp.generation.GenerationTaskRepository
import io.github.ayaseminami.gnbp.generation.ReferenceAssetSnapshot
import io.github.ayaseminami.gnbp.generation.TaskCancellationReason
import io.github.ayaseminami.gnbp.generation.TaskFailureReason
import io.github.ayaseminami.gnbp.generation.TaskId
import io.github.ayaseminami.gnbp.generation.TaskOutcomeUnknownReason
import io.github.ayaseminami.gnbp.generation.TaskRequestSnapshot
import io.github.ayaseminami.gnbp.generation.TaskStatus
import io.github.ayaseminami.gnbp.media.AssetRef
import io.github.ayaseminami.gnbp.media.MediaAssetId
import io.github.ayaseminami.gnbp.persistence.profile.ProviderKind
import io.github.ayaseminami.gnbp.persistence.room.GenerationTaskDao
import io.github.ayaseminami.gnbp.persistence.room.GenerationTaskEntity
import io.github.ayaseminami.gnbp.provider.GenerationParameters
import io.github.ayaseminami.gnbp.provider.transport.ProfileId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class RoomGenerationTaskRepository internal constructor(
    private val taskDao: GenerationTaskDao,
) : GenerationTaskRepository {
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    override fun observeTasks(): Flow<List<GenerationTask>> = taskDao.observeAll().map { tasks ->
        tasks.mapNotNull(::decodeOrNull)
    }

    override suspend fun loadTasks(): List<GenerationTask> = taskDao.findAll().mapNotNull(::decodeOrNull)

    override suspend fun findTask(id: TaskId): GenerationTask? =
        taskDao.findById(id.value)?.let(::decodeOrNull)

    override suspend fun insertTasks(newTasks: List<GenerationTask>) {
        taskDao.upsertAll(newTasks.map { task -> task.toEntity() })
    }

    override suspend fun updateTask(task: GenerationTask) {
        taskDao.upsert(task.toEntity())
    }

    private fun decodeOrNull(entity: GenerationTaskEntity): GenerationTask? =
        runCatching { entity.toTask() }.getOrNull()

    private fun GenerationTask.toEntity(): GenerationTaskEntity {
        val terminalReason = when (val value = status) {
            is TaskStatus.Failed -> value.reason.name
            is TaskStatus.Cancelled -> value.reason.name
            is TaskStatus.OutcomeUnknown -> value.reason.name
            else -> null
        }
        val asset = (status as? TaskStatus.Succeeded)?.asset
        return GenerationTaskEntity(
            id = id.value,
            requestJson = json.encodeToString(request.toPersisted()),
            status = status.persistedName(),
            createdAt = createdAtEpochMillis,
            startedAt = startedAtEpochMillis,
            finishedAt = finishedAtEpochMillis,
            sourceTaskId = sourceTaskId?.value,
            terminalReason = terminalReason,
            resultAssetId = asset?.id?.value,
            resultUri = asset?.uri?.toString(),
            resultDisplayName = asset?.displayName,
            resultMimeType = asset?.mimeType,
            resultByteSize = asset?.byteSize,
        )
    }

    private fun GenerationTaskEntity.toTask(): GenerationTask {
        val request = json.decodeFromString<PersistedTaskRequest>(requestJson).toDomain()
        val taskStatus = when (status) {
            "QUEUED" -> TaskStatus.Queued
            "RUNNING" -> TaskStatus.Running
            "SUCCEEDED" -> TaskStatus.Succeeded(
                AssetRef(
                    id = MediaAssetId(requireNotNull(resultAssetId)),
                    uri = requireNotNull(resultUri).toUri(),
                    displayName = requireNotNull(resultDisplayName),
                    mimeType = requireNotNull(resultMimeType),
                    byteSize = requireNotNull(resultByteSize),
                ),
            )
            "FAILED" -> TaskStatus.Failed(
                TaskFailureReason.valueOf(requireNotNull(terminalReason)),
            )
            "CANCELLED" -> TaskStatus.Cancelled(
                TaskCancellationReason.valueOf(requireNotNull(terminalReason)),
            )
            "OUTCOME_UNKNOWN" -> TaskStatus.OutcomeUnknown(
                TaskOutcomeUnknownReason.valueOf(requireNotNull(terminalReason)),
            )
            else -> error("Unknown persisted task status")
        }
        return GenerationTask(
            id = TaskId(id),
            request = request,
            status = taskStatus,
            createdAtEpochMillis = createdAt,
            startedAtEpochMillis = startedAt,
            finishedAtEpochMillis = finishedAt,
            sourceTaskId = sourceTaskId?.let(::TaskId),
        )
    }
}

@Serializable
private data class PersistedTaskRequest(
    val profileId: String,
    val profileName: String,
    val providerKind: String,
    val model: String,
    val prompt: String,
    val parameters: PersistedGenerationParameters,
    val references: List<PersistedReferenceAsset>,
) {
    fun toDomain(): TaskRequestSnapshot = TaskRequestSnapshot(
        profileId = ProfileId(profileId),
        profileName = profileName,
        providerKind = ProviderKind.valueOf(providerKind),
        model = model,
        prompt = prompt,
        parameters = parameters.toDomain(),
        references = references.map(PersistedReferenceAsset::toDomain),
    )
}

@Serializable
private data class PersistedGenerationParameters(
    val kind: String,
    val aspectRatio: String? = null,
    val imageSize: String? = null,
    val temperature: Double? = null,
    val size: String? = null,
    val quality: String? = null,
) {
    fun toDomain(): GenerationParameters = when (kind) {
        "GEMINI" -> GenerationParameters.Gemini(
            aspectRatio = requireNotNull(aspectRatio),
            imageSize = requireNotNull(imageSize),
            temperature = requireNotNull(temperature),
        )
        "OPENAI" -> GenerationParameters.OpenAi(
            size = requireNotNull(size),
            quality = requireNotNull(quality),
        )
        else -> error("Unknown persisted generation parameters")
    }
}

@Serializable
private data class PersistedReferenceAsset(
    val id: String,
    val displayName: String,
    val mimeType: String,
) {
    fun toDomain(): ReferenceAssetSnapshot = ReferenceAssetSnapshot(
        id = MediaAssetId(id),
        displayName = displayName,
        mimeType = mimeType,
    )
}

private fun TaskRequestSnapshot.toPersisted() = PersistedTaskRequest(
    profileId = profileId.value,
    profileName = profileName,
    providerKind = providerKind.name,
    model = model,
    prompt = prompt,
    parameters = when (val value = parameters) {
        is GenerationParameters.Gemini -> PersistedGenerationParameters(
            kind = "GEMINI",
            aspectRatio = value.aspectRatio,
            imageSize = value.imageSize,
            temperature = value.temperature,
        )
        is GenerationParameters.OpenAi -> PersistedGenerationParameters(
            kind = "OPENAI",
            size = value.size,
            quality = value.quality,
        )
    },
    references = references.map { reference ->
        PersistedReferenceAsset(
            id = reference.id.value,
            displayName = reference.displayName,
            mimeType = reference.mimeType,
        )
    },
)

private fun TaskStatus.persistedName(): String = when (this) {
    TaskStatus.Queued -> "QUEUED"
    TaskStatus.Running -> "RUNNING"
    is TaskStatus.Succeeded -> "SUCCEEDED"
    is TaskStatus.Failed -> "FAILED"
    is TaskStatus.Cancelled -> "CANCELLED"
    is TaskStatus.OutcomeUnknown -> "OUTCOME_UNKNOWN"
}
