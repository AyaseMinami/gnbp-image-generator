package io.github.ayaseminami.gnbp.persistence.prompt

import io.github.ayaseminami.gnbp.persistence.room.PromptDao
import io.github.ayaseminami.gnbp.persistence.room.PromptEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class RoomPromptRepository internal constructor(
    private val promptDao: PromptDao,
) : PromptRepository {
    override fun observePrompts(): Flow<List<PromptPreset>> = promptDao.observeAll().map { prompts ->
        prompts.map(PromptEntity::toPrompt)
    }

    override suspend fun loadPrompt(id: PromptId): PromptPreset? =
        promptDao.findById(id.value)?.toPrompt()

    override suspend fun savePrompt(prompt: PromptPreset) {
        promptDao.upsert(prompt.toEntity())
    }

    override suspend fun deletePrompt(id: PromptId): Boolean = promptDao.deleteById(id.value) > 0
}

private fun PromptPreset.toEntity(): PromptEntity = PromptEntity(
    id = id.value,
    name = name,
    content = content,
    sortOrder = sortOrder,
)

private fun PromptEntity.toPrompt(): PromptPreset = PromptPreset(
    id = PromptId(id),
    name = name,
    content = content,
    sortOrder = sortOrder,
)
