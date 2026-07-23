package io.github.ayaseminami.gnbp.persistence.result

import io.github.ayaseminami.gnbp.generation.GeneratedAssetReference
import io.github.ayaseminami.gnbp.generation.TaskId
import io.github.ayaseminami.gnbp.persistence.room.GeneratedResultDao
import io.github.ayaseminami.gnbp.persistence.room.GeneratedResultEntity
import io.github.ayaseminami.gnbp.persistence.task.TaskRequestJsonCodec
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class RoomGeneratedResultRepository internal constructor(
    private val resultDao: GeneratedResultDao,
) : GeneratedResultRepository {
    override fun observeResults(): Flow<List<GeneratedResult>> = resultDao.observeAll().map { results ->
        results.mapNotNull(::decodeOrNull)
    }

    override suspend fun loadResults(): List<GeneratedResult> =
        resultDao.findAll().mapNotNull(::decodeOrNull)

    override suspend fun findResult(id: GeneratedResultId): GeneratedResult? =
        resultDao.findById(id.value)?.let(::decodeOrNull)

    override suspend fun setFavorite(id: GeneratedResultId, favorite: Boolean): Boolean =
        resultDao.updateFavorite(id.value, favorite) == 1

    private fun decodeOrNull(entity: GeneratedResultEntity): GeneratedResult? =
        runCatching { entity.toDomain() }.getOrNull()

    private fun GeneratedResultEntity.toDomain(): GeneratedResult = GeneratedResult(
        id = GeneratedResultId(id),
        sourceTaskId = TaskId(sourceTaskId),
        request = TaskRequestJsonCodec.decode(requestJson),
        asset = GeneratedAssetReference(
            id = assetId,
            location = assetUri,
            displayName = assetDisplayName,
            mimeType = assetMimeType,
            byteSize = assetByteSize,
        ),
        createdAtEpochMillis = createdAt,
        isFavorite = isFavorite,
    )
}
