package io.github.ayaseminami.gnbp.provider

import io.github.ayaseminami.gnbp.provider.transport.HttpHeader
import io.github.ayaseminami.gnbp.provider.transport.HttpMethod
import io.github.ayaseminami.gnbp.provider.transport.MultipartField
import io.github.ayaseminami.gnbp.provider.transport.MultipartFile
import io.github.ayaseminami.gnbp.provider.transport.ProviderHttpBody
import io.github.ayaseminami.gnbp.provider.transport.ProviderHttpCall
import io.github.ayaseminami.gnbp.provider.transport.ProviderHttpResult
import io.github.ayaseminami.gnbp.provider.transport.ProviderHttpTransport
import io.github.ayaseminami.gnbp.provider.transport.SensitiveValue
import io.github.ayaseminami.gnbp.provider.transport.TransportBinding
import java.util.Base64
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class OpenAiProvider(
    private val transport: ProviderHttpTransport,
    private val binding: TransportBinding,
    private val apiKey: ApiKey,
) : ImageGenerationProvider {
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun generate(
        request: ImageGenerationRequest,
        cancellation: GenerationCancellation,
    ): ImageGenerationResult {
        val parameters = request.parameters as? GenerationParameters.OpenAi
            ?: return ImageGenerationResult.Failure(
                ProviderError.InvalidRequest("OpenAI-compatible parameters are required"),
            )
        val response = transport.execute(
            ProviderHttpCall(
                binding = binding,
                method = HttpMethod.Post,
                pathSegments = if (request.referenceImages.isEmpty()) {
                    listOf("v1", "images", "generations")
                } else {
                    listOf("v1", "images", "edits")
                },
                headers = listOf(
                    HttpHeader("Accept", SensitiveValue("application/json"), isSensitive = false),
                    HttpHeader(
                        "Authorization",
                        SensitiveValue("Bearer ${apiKey.reveal()}"),
                        isSensitive = true,
                    ),
                ),
                body = if (request.referenceImages.isEmpty()) {
                    ProviderHttpBody.Json(
                        json.encodeToString(
                            OpenAiGenerationBody(
                                model = request.model,
                                prompt = request.prompt,
                                n = 1,
                                size = parameters.size,
                                quality = parameters.quality,
                            ),
                        ),
                    )
                } else {
                    request.toMultipartBody(parameters)
                },
                cancellation = cancellation.transportCancellation,
            ),
        )
        return when (response) {
            is ProviderHttpResult.Failure -> ImageGenerationResult.Failure(
                ProviderError.Transport(response.error),
            )
            is ProviderHttpResult.Response -> parseResponse(response, request.prompt)
        }
    }

    private fun ImageGenerationRequest.toMultipartBody(
        parameters: GenerationParameters.OpenAi,
    ) = ProviderHttpBody.Multipart(
        fields = listOf(
            MultipartField("model", model),
            MultipartField("prompt", prompt),
            MultipartField("n", "1"),
            MultipartField("size", parameters.size),
            MultipartField("quality", parameters.quality),
        ),
        files = referenceImages.map { image ->
            MultipartFile(
                name = "image",
                fileName = image.displayName,
                mimeType = image.mimeType,
                bytes = image.bytes,
            )
        },
    )

    private fun parseResponse(
        response: ProviderHttpResult.Response,
        prompt: String,
    ): ImageGenerationResult {
        if (response.statusCode !in 200..299) {
            return ImageGenerationResult.Failure(
                ProviderError.HttpStatus(response.statusCode, response.body.openAiProviderMessage(prompt)),
            )
        }
        val root = try {
            json.parseToJsonElement(response.body.decodeToString()).jsonObject
        } catch (_: SerializationException) {
            return ImageGenerationResult.Failure(
                ProviderError.MalformedResponse("OpenAI-compatible response is not valid JSON"),
            )
        } catch (_: IllegalArgumentException) {
            return ImageGenerationResult.Failure(
                ProviderError.MalformedResponse("OpenAI-compatible response is not a JSON object"),
            )
        }
        val imageData = runCatching {
            when (val data = root["data"]) {
                is JsonArray -> data.firstOrNull()?.jsonObject?.get("b64_json")
                is JsonObject -> data["b64_json"]
                else -> null
            }?.jsonPrimitive?.content
        }.getOrNull()
            ?: return ImageGenerationResult.Failure(ProviderError.NoImageData)
        val bytes = try {
            Base64.getDecoder().decode(imageData)
        } catch (_: IllegalArgumentException) {
            return ImageGenerationResult.Failure(
                ProviderError.MalformedResponse("OpenAI-compatible image data is not valid base64"),
            )
        }
        return ImageGenerationResult.Success(GeneratedImage(bytes, "image/png"))
    }

    private fun ByteArray.openAiProviderMessage(prompt: String): String? {
        val text = decodeToString()
        val structuredMessage = runCatching {
            json.parseToJsonElement(text).jsonObject["error"]
                ?.jsonObject
                ?.get("message")
                ?.jsonPrimitive
                ?.content
        }.getOrNull() ?: return null
        return sanitizeProviderMessage(
            value = structuredMessage,
            apiKey = apiKey,
            requestPrompt = prompt,
            endpointHost = binding.endpoint.authority.asciiHost,
        )
    }
}

@Serializable
private data class OpenAiGenerationBody(
    val model: String,
    val prompt: String,
    val n: Int,
    val size: String,
    val quality: String,
)
