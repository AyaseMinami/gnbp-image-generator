package io.github.ayaseminami.gnbp.persistence.task

import io.github.ayaseminami.gnbp.generation.GenerationProviderKind
import io.github.ayaseminami.gnbp.generation.ReferenceAssetSnapshot
import io.github.ayaseminami.gnbp.generation.TaskRequestSnapshot
import io.github.ayaseminami.gnbp.provider.GenerationParameters
import io.github.ayaseminami.gnbp.provider.transport.ProfileId
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal object TaskRequestJsonCodec {
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    fun encode(request: TaskRequestSnapshot): String = json.encodeToString(request.toPersisted())

    fun decode(encoded: String): TaskRequestSnapshot =
        json.decodeFromString<PersistedTaskRequest>(encoded).toDomain()
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
        providerKind = GenerationProviderKind.valueOf(providerKind),
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
        id = id,
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
            id = reference.id,
            displayName = reference.displayName,
            mimeType = reference.mimeType,
        )
    },
)
