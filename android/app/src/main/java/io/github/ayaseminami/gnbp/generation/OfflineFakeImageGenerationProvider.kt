package io.github.ayaseminami.gnbp.generation

import io.github.ayaseminami.gnbp.provider.GeneratedImage
import io.github.ayaseminami.gnbp.provider.GenerationCancellation
import io.github.ayaseminami.gnbp.provider.ImageGenerationProvider
import io.github.ayaseminami.gnbp.provider.ImageGenerationRequest
import io.github.ayaseminami.gnbp.provider.ImageGenerationResult

class OfflineFakeImageGenerationProvider(
    image: GeneratedImage,
) : ImageGenerationProvider {
    private val response = GeneratedImage(image.bytes.copyOf(), image.mimeType)

    override suspend fun generate(
        request: ImageGenerationRequest,
        cancellation: GenerationCancellation,
    ): ImageGenerationResult = ImageGenerationResult.Success(
        GeneratedImage(response.bytes.copyOf(), response.mimeType),
    )
}
