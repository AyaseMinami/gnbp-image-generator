package io.github.ayaseminami.gnbp.persistence.room

import androidx.room.Dao
import androidx.room.Query
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
