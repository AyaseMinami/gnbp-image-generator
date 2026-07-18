package io.github.ayaseminami.gnbp.persistence.prompt

import kotlinx.coroutines.flow.Flow

@JvmInline
value class PromptId(val value: String) {
    init {
        require(value.isNotBlank()) { "Prompt ID must not be blank" }
    }
}

data class PromptPreset(
    val id: PromptId,
    val name: String,
    val content: String,
    val sortOrder: Int,
) {
    init {
        require(name.isNotBlank()) { "Prompt name must not be blank" }
        require(content.isNotBlank()) { "Prompt content must not be blank" }
        require(sortOrder >= 0) { "Prompt sort order must not be negative" }
    }

    override fun toString(): String =
        "PromptPreset(id=[REDACTED], name=[REDACTED], content=[REDACTED], sortOrder=$sortOrder)"
}

interface PromptRepository {
    fun observePrompts(): Flow<List<PromptPreset>>

    suspend fun loadPrompt(id: PromptId): PromptPreset?

    suspend fun savePrompt(prompt: PromptPreset)

    suspend fun deletePrompt(id: PromptId): Boolean
}
