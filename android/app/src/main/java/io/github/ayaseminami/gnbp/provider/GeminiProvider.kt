package io.github.ayaseminami.gnbp.provider

import io.github.ayaseminami.gnbp.provider.transport.HttpMethod
import io.github.ayaseminami.gnbp.provider.transport.HttpQueryParameter
import io.github.ayaseminami.gnbp.provider.transport.ProviderHttpBody
import io.github.ayaseminami.gnbp.provider.transport.ProviderHttpCall
import io.github.ayaseminami.gnbp.provider.transport.ProviderHttpResult
import io.github.ayaseminami.gnbp.provider.transport.ProviderHttpTransport
import io.github.ayaseminami.gnbp.provider.transport.SensitiveValue
import io.github.ayaseminami.gnbp.provider.transport.TransportBinding
import java.util.Base64
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class GeminiProvider(
    private val transport: ProviderHttpTransport,
    private val binding: TransportBinding,
    private val apiKey: ApiKey,
) : ImageGenerationProvider {
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    override suspend fun generate(request: ImageGenerationRequest): ImageGenerationResult {
        val parameters = request.parameters as? GenerationParameters.Gemini
            ?: return ImageGenerationResult.Failure(
                ProviderError.InvalidRequest("Gemini parameters are required"),
            )
        val response = transport.execute(
            ProviderHttpCall(
                binding = binding,
                method = HttpMethod.Post,
                pathSegments = listOf("v1beta", "models", "${request.model}:generateContent"),
                queryParameters = listOf(
                    HttpQueryParameter(
                        name = "key",
                        value = SensitiveValue(apiKey.reveal()),
                        isSensitive = true,
                    ),
                ),
                body = ProviderHttpBody.Json(
                    json.encodeToString(request.toGeminiBody(parameters)),
                ),
            ),
        )
        return when (response) {
            is ProviderHttpResult.Failure -> ImageGenerationResult.Failure(
                ProviderError.Transport(response.error),
            )
            is ProviderHttpResult.Response -> parseResponse(response)
        }
    }

    private fun parseResponse(response: ProviderHttpResult.Response): ImageGenerationResult {
        if (response.statusCode !in 200..299) {
            return ImageGenerationResult.Failure(
                ProviderError.HttpStatus(response.statusCode, response.body.sanitizedProviderMessage(apiKey)),
            )
        }
        val payload = try {
            json.decodeFromString<GeminiResponse>(response.body.decodeToString())
        } catch (_: SerializationException) {
            return ImageGenerationResult.Failure(
                ProviderError.MalformedResponse("Gemini response is not valid JSON"),
            )
        }
        if (payload.candidates.isEmpty()) {
            return ImageGenerationResult.Failure(
                ProviderError.Blocked(payload.promptFeedback?.blockReason ?: "Unknown"),
            )
        }
        val image = payload.candidates.first().content?.parts.orEmpty()
            .firstNotNullOfOrNull { it.inlineData ?: it.inlineDataSnake }
            ?: return ImageGenerationResult.Failure(ProviderError.NoImageData)
        val bytes = try {
            Base64.getDecoder().decode(image.data)
        } catch (_: IllegalArgumentException) {
            return ImageGenerationResult.Failure(
                ProviderError.MalformedResponse("Gemini image data is not valid base64"),
            )
        }
        return ImageGenerationResult.Success(GeneratedImage(bytes, image.mimeType))
    }

    private fun ImageGenerationRequest.toGeminiBody(
        parameters: GenerationParameters.Gemini,
    ): GeminiRequestBody {
        val parts = buildList {
            add(GeminiPart(text = prompt))
            referenceImages.forEach { image ->
                add(
                    GeminiPart(
                        inlineData = GeminiInlineData(
                            mimeType = image.mimeType,
                            data = Base64.getEncoder().encodeToString(image.bytes),
                        ),
                    ),
                )
                add(GeminiPart(text = "\n[Reference Image: ${image.displayName}]"))
            }
        }
        return GeminiRequestBody(
            contents = listOf(GeminiContent(parts)),
            safetySettings = SAFETY_CATEGORIES.map { GeminiSafetySetting(it, "BLOCK_NONE") },
            generationConfig = GeminiGenerationConfig(
                temperature = parameters.temperature,
                responseModalities = listOf("IMAGE"),
                imageConfig = GeminiImageConfig(parameters.aspectRatio, parameters.imageSize),
            ),
        )
    }

    private companion object {
        val SAFETY_CATEGORIES = listOf(
            "HARM_CATEGORY_HARASSMENT",
            "HARM_CATEGORY_HATE_SPEECH",
            "HARM_CATEGORY_SEXUALLY_EXPLICIT",
            "HARM_CATEGORY_DANGEROUS_CONTENT",
        )
    }
}

@Serializable
private data class GeminiRequestBody(
    val contents: List<GeminiContent>,
    val safetySettings: List<GeminiSafetySetting>,
    val generationConfig: GeminiGenerationConfig,
)

@Serializable
private data class GeminiContent(val parts: List<GeminiPart>)

@Serializable
private data class GeminiPart(
    val text: String? = null,
    val inlineData: GeminiInlineData? = null,
)

@Serializable
private data class GeminiInlineData(
    val mimeType: String,
    val data: String,
)

@Serializable
private data class GeminiSafetySetting(
    val category: String,
    val threshold: String,
)

@Serializable
private data class GeminiGenerationConfig(
    val temperature: Double,
    val responseModalities: List<String>,
    val imageConfig: GeminiImageConfig,
)

@Serializable
private data class GeminiImageConfig(
    val aspectRatio: String,
    val imageSize: String,
)

@Serializable
private data class GeminiResponse(
    val candidates: List<GeminiCandidate> = emptyList(),
    val promptFeedback: GeminiPromptFeedback? = null,
)

@Serializable
private data class GeminiCandidate(val content: GeminiResponseContent? = null)

@Serializable
private data class GeminiResponseContent(val parts: List<GeminiResponsePart> = emptyList())

@Serializable
private data class GeminiResponsePart(
    val inlineData: GeminiInlineData? = null,
    @SerialName("inline_data") val inlineDataSnake: GeminiInlineData? = null,
)

@Serializable
private data class GeminiPromptFeedback(val blockReason: String? = null)
