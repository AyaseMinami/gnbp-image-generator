package io.github.ayaseminami.gnbp.persistence.task

import io.github.ayaseminami.gnbp.generation.DirectReplacementCommit
import io.github.ayaseminami.gnbp.generation.GeneratedAssetReference
import io.github.ayaseminami.gnbp.generation.GenerationCompletionRepository
import io.github.ayaseminami.gnbp.generation.GenerationTask
import io.github.ayaseminami.gnbp.generation.GenerationTaskRepository
import io.github.ayaseminami.gnbp.generation.TaskCancellationReason
import io.github.ayaseminami.gnbp.generation.TaskFailureReason
import io.github.ayaseminami.gnbp.generation.TaskId
import io.github.ayaseminami.gnbp.generation.TaskOutcomeUnknownReason
import io.github.ayaseminami.gnbp.generation.TaskStatus
import io.github.ayaseminami.gnbp.persistence.room.DirectReplacementEntityCommit
import io.github.ayaseminami.gnbp.persistence.room.GenerationTaskDao
import io.github.ayaseminami.gnbp.persistence.room.GenerationTaskEntity
import io.github.ayaseminami.gnbp.persistence.room.GeneratedResultEntity
import io.github.ayaseminami.gnbp.persistence.result.GeneratedResultId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class RoomGenerationTaskRepository internal constructor(
    private val taskDao: GenerationTaskDao,
) : GenerationTaskRepository, GenerationCompletionRepository {

    override fun observeTasks(): Flow<List<GenerationTask>> = taskDao.observeAll().map { tasks ->
        tasks.mapNotNull(::decodeOrNull)
    }

    override suspend fun loadTasks(): List<GenerationTask> = taskDao.findAll().mapNotNull(::decodeOrNull)

    override suspend fun findTask(id: TaskId): GenerationTask? =
        taskDao.findById(id.value)?.let(::decodeOrNull)

    override suspend fun findDirectReplacement(sourceTaskId: TaskId): GenerationTask? =
        taskDao.findDirectReplacement(sourceTaskId.value)?.let(::decodeOrNull)

    override suspend fun commitDirectReplacement(task: GenerationTask): DirectReplacementCommit {
        requireNotNull(task.sourceTaskId) { "A direct replacement must identify its source task" }
        return when (val committed = taskDao.commitDirectReplacement(task.toEntity())) {
            is DirectReplacementEntityCommit.Inserted -> DirectReplacementCommit.Inserted(
                TaskId(committed.task.id),
            )
            is DirectReplacementEntityCommit.Existing -> DirectReplacementCommit.Existing(
                TaskId(committed.task.id),
            )
        }
    }

    override suspend fun insertTasks(newTasks: List<GenerationTask>) {
        taskDao.upsertAll(newTasks.map { task -> task.toEntity() })
    }

    override suspend fun updateTask(task: GenerationTask) {
        taskDao.upsert(task.toEntity())
    }

    override suspend fun deleteTerminalTasks(taskIds: Set<TaskId>): Set<TaskId> =
        taskDao.deleteTerminalTasks(taskIds.map(TaskId::value))
            .mapTo(mutableSetOf()) { entity -> TaskId(entity.id) }

    override suspend fun commitSucceededTask(task: GenerationTask) {
        val entity = task.toEntity()
        val asset = requireNotNull((task.status as? TaskStatus.Succeeded)?.asset) {
            "Only a successful task can create a generated result"
        }
        taskDao.commitSucceededTask(
            task = entity,
            result = GeneratedResultEntity(
                id = GeneratedResultId.forTask(task.id).value,
                sourceTaskId = task.id.value,
                requestJson = entity.requestJson,
                createdAt = task.finishedAtEpochMillis ?: task.createdAtEpochMillis,
                assetId = asset.id,
                assetUri = asset.location,
                assetDisplayName = asset.displayName,
                assetMimeType = asset.mimeType,
                assetByteSize = asset.byteSize,
            ),
        )
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
            requestJson = TaskRequestJsonCodec.encode(request),
            status = status.persistedName(),
            createdAt = createdAtEpochMillis,
            startedAt = startedAtEpochMillis,
            finishedAt = finishedAtEpochMillis,
            sourceTaskId = sourceTaskId?.value,
            terminalReason = terminalReason,
            resultAssetId = asset?.id,
            resultUri = asset?.location,
            resultDisplayName = asset?.displayName,
            resultMimeType = asset?.mimeType,
            resultByteSize = asset?.byteSize,
        )
    }

    private fun GenerationTaskEntity.toTask(): GenerationTask {
        val request = TaskRequestJsonCodec.decode(requestJson)
        val taskStatus = when (status) {
            "QUEUED" -> TaskStatus.Queued
            "RUNNING" -> TaskStatus.Running
            "SUCCEEDED" -> TaskStatus.Succeeded(
                GeneratedAssetReference(
                    id = requireNotNull(resultAssetId),
                    location = requireNotNull(resultUri),
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

private fun TaskStatus.persistedName(): String = when (this) {
    TaskStatus.Queued -> "QUEUED"
    TaskStatus.Running -> "RUNNING"
    is TaskStatus.Succeeded -> "SUCCEEDED"
    is TaskStatus.Failed -> "FAILED"
    is TaskStatus.Cancelled -> "CANCELLED"
    is TaskStatus.OutcomeUnknown -> "OUTCOME_UNKNOWN"
}
