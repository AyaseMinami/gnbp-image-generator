package io.github.ayaseminami.gnbp.persistence.room

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
internal interface ProfileDao {
    @Query("SELECT * FROM profiles ORDER BY sort_order, id")
    fun observeAll(): Flow<List<ProfileEntity>>

    @Query("SELECT * FROM profiles WHERE id = :id")
    suspend fun findById(id: String): ProfileEntity?

    @Upsert
    suspend fun upsert(profile: ProfileEntity)

    @Query("DELETE FROM profiles WHERE id = :id")
    suspend fun deleteById(id: String): Int
}

@Dao
internal interface PromptDao {
    @Query("SELECT * FROM prompts ORDER BY sort_order, id")
    fun observeAll(): Flow<List<PromptEntity>>

    @Query("SELECT * FROM prompts WHERE id = :id")
    suspend fun findById(id: String): PromptEntity?

    @Upsert
    suspend fun upsert(prompt: PromptEntity)

    @Query("DELETE FROM prompts WHERE id = :id")
    suspend fun deleteById(id: String): Int
}

@Dao
internal interface GenerationTaskDao {
    @Query("SELECT * FROM generation_tasks ORDER BY created_at DESC, id DESC")
    fun observeAll(): Flow<List<GenerationTaskEntity>>

    @Query("SELECT * FROM generation_tasks ORDER BY created_at DESC, id DESC")
    suspend fun findAll(): List<GenerationTaskEntity>

    @Query("SELECT * FROM generation_tasks WHERE id = :id")
    suspend fun findById(id: String): GenerationTaskEntity?

    @Query(
        "SELECT * FROM generation_tasks WHERE source_task_id = :sourceTaskId " +
            "ORDER BY created_at, id LIMIT 1",
    )
    suspend fun findDirectReplacement(sourceTaskId: String): GenerationTaskEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(task: GenerationTaskEntity): Long

    @Transaction
    suspend fun commitDirectReplacement(task: GenerationTaskEntity): DirectReplacementEntityCommit {
        val sourceTaskId = requireNotNull(task.sourceTaskId)
        if (insertIfAbsent(task) != -1L) {
            return DirectReplacementEntityCommit.Inserted(task)
        }
        return DirectReplacementEntityCommit.Existing(
            requireNotNull(findDirectReplacement(sourceTaskId)) {
                "A task ID collision prevented the direct replacement commit"
            },
        )
    }

    @Upsert
    suspend fun upsertAll(tasks: List<GenerationTaskEntity>)

    @Upsert
    suspend fun upsert(task: GenerationTaskEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertResultIfAbsent(result: GeneratedResultEntity): Long

    @Query("SELECT * FROM generated_results WHERE source_task_id = :sourceTaskId")
    suspend fun findResultBySourceTaskId(sourceTaskId: String): GeneratedResultEntity?

    @Transaction
    suspend fun commitSucceededTask(
        task: GenerationTaskEntity,
        result: GeneratedResultEntity,
    ): GeneratedResultEntity {
        upsert(task)
        insertResultIfAbsent(result)
        return requireNotNull(findResultBySourceTaskId(task.id)) {
            "A generated-result ID collision prevented the successful task commit"
        }
    }
}

@Dao
internal interface GeneratedResultDao {
    @Query("SELECT * FROM generated_results ORDER BY created_at DESC, id DESC")
    fun observeAll(): Flow<List<GeneratedResultEntity>>

    @Query("SELECT * FROM generated_results ORDER BY created_at DESC, id DESC")
    suspend fun findAll(): List<GeneratedResultEntity>

    @Query("SELECT * FROM generated_results WHERE id = :id")
    suspend fun findById(id: String): GeneratedResultEntity?

    @Query("UPDATE generated_results SET is_favorite = :favorite WHERE id = :id")
    suspend fun updateFavorite(id: String, favorite: Boolean): Int
}

internal sealed interface DirectReplacementEntityCommit {
    data class Inserted(val task: GenerationTaskEntity) : DirectReplacementEntityCommit

    data class Existing(val task: GenerationTaskEntity) : DirectReplacementEntityCommit
}
