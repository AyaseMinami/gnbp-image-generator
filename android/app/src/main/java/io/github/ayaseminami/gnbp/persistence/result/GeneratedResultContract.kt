package io.github.ayaseminami.gnbp.persistence.result

import io.github.ayaseminami.gnbp.generation.GeneratedAssetReference
import io.github.ayaseminami.gnbp.generation.TaskId
import io.github.ayaseminami.gnbp.generation.TaskRequestSnapshot
import kotlinx.coroutines.flow.Flow

@JvmInline
value class GeneratedResultId(val value: String) {
    init {
        require(value.isNotBlank()) { "Generated result ID must not be blank" }
        require(value.all { it.isLetterOrDigit() || it == '-' || it == '_' }) {
            "Generated result ID contains unsupported characters"
        }
    }

    companion object {
        fun forTask(taskId: TaskId): GeneratedResultId = GeneratedResultId("result-${taskId.value}")
    }
}

data class GeneratedResult(
    val id: GeneratedResultId,
    val sourceTaskId: TaskId,
    val request: TaskRequestSnapshot,
    val asset: GeneratedAssetReference,
    val createdAtEpochMillis: Long,
    val isFavorite: Boolean = false,
) {
    override fun toString(): String =
        "GeneratedResult(id=[REDACTED], sourceTaskId=[REDACTED], request=[REDACTED], " +
            "asset=[REDACTED], createdAt=[REDACTED], isFavorite=$isFavorite)"
}

interface GeneratedResultRepository {
    fun observeResults(): Flow<List<GeneratedResult>>

    suspend fun loadResults(): List<GeneratedResult>

    suspend fun findResult(id: GeneratedResultId): GeneratedResult?

    suspend fun setFavorite(id: GeneratedResultId, favorite: Boolean): Boolean
}
