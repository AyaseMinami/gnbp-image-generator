package io.github.ayaseminami.gnbp.provider

import io.github.ayaseminami.gnbp.provider.transport.TransportFailure
import io.github.ayaseminami.gnbp.provider.transport.DeliveryCertainty
import io.github.ayaseminami.gnbp.provider.transport.TransportCancellation
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import okhttp3.HttpUrl

class ApiKey(
    private val rawValue: String,
) {
    init {
        require(rawValue.isNotBlank()) { "API key must not be blank" }
        require(rawValue.none { it.code < 0x20 || it.code == 0x7f }) {
            "API key must not contain control characters"
        }
    }

    internal fun reveal(): String = rawValue

    override fun toString(): String = "[REDACTED]"
}

data class ReferenceImage(
    val bytes: ByteArray,
    val mimeType: String,
    val displayName: String,
) {
    init {
        require(bytes.isNotEmpty()) { "Reference image must not be empty" }
        require(mimeType.startsWith("image/")) { "Reference MIME type must be an image" }
        require(displayName.isNotBlank()) { "Reference display name must not be blank" }
    }

    override fun toString(): String =
        "ReferenceImage(bytes=[REDACTED], mimeType=$mimeType, displayName=[REDACTED])"
}

data class ImageGenerationRequest(
    val model: String,
    val prompt: String,
    val parameters: GenerationParameters,
    val referenceImages: List<ReferenceImage> = emptyList(),
) {
    init {
        require(model.isNotBlank()) { "Model must not be blank" }
        require(prompt.isNotBlank()) { "Prompt must not be blank" }
    }
}

sealed interface GenerationParameters {
    data class Gemini(
        val aspectRatio: String,
        val imageSize: String,
        val temperature: Double,
    ) : GenerationParameters {
        init {
            require(aspectRatio.isNotBlank()) { "Gemini aspect ratio must not be blank" }
            require(imageSize.isNotBlank()) { "Gemini image size must not be blank" }
            require(temperature.isFinite()) { "Gemini temperature must be finite" }
        }
    }

    data class OpenAi(
        val size: String,
        val quality: String,
    ) : GenerationParameters {
        init {
            require(size.isNotBlank()) { "OpenAI-compatible size must not be blank" }
            require(quality.isNotBlank()) { "OpenAI-compatible quality must not be blank" }
        }
    }
}

data class GeneratedImage(
    val bytes: ByteArray,
    val mimeType: String,
) {
    override fun toString(): String = "GeneratedImage(bytes=[REDACTED], mimeType=$mimeType)"
}

sealed interface ImageGenerationResult {
    data class Success(val image: GeneratedImage) : ImageGenerationResult

    data class Failure(val error: ProviderError) : ImageGenerationResult
}

sealed interface ProviderError {
    val certainty: DeliveryCertainty

    data class InvalidRequest(
        val reason: String,
        override val certainty: DeliveryCertainty = DeliveryCertainty.NotSent,
    ) : ProviderError {
        override fun toString(): String =
            "InvalidRequest(reason=[REDACTED], certainty=$certainty)"
    }

    data class Blocked(
        val reason: String,
        override val certainty: DeliveryCertainty = DeliveryCertainty.Responded,
    ) : ProviderError {
        override fun toString(): String = "Blocked(reason=[REDACTED], certainty=$certainty)"
    }

    data class HttpStatus(
        val statusCode: Int,
        val providerMessage: String?,
        override val certainty: DeliveryCertainty = DeliveryCertainty.Responded,
    ) : ProviderError {
        override fun toString(): String =
            "HttpStatus(statusCode=$statusCode, providerMessage=[REDACTED], certainty=$certainty)"
    }

    data class Transport(val failure: TransportFailure) : ProviderError {
        override val certainty: DeliveryCertainty = failure.certainty

        override fun toString(): String =
            "Transport(failure=${failure::class.simpleName}, certainty=$certainty)"
    }

    data class MalformedResponse(
        val reason: String,
        override val certainty: DeliveryCertainty = DeliveryCertainty.Responded,
    ) : ProviderError {
        override fun toString(): String =
            "MalformedResponse(reason=[REDACTED], certainty=$certainty)"
    }

    data object NoImageData : ProviderError {
        override val certainty: DeliveryCertainty = DeliveryCertainty.Responded
    }
}

class GenerationCancellation internal constructor(
    internal val transportCancellation: TransportCancellation,
) {
    constructor() : this(TransportCancellation())

    fun cancel() = transportCancellation.cancel()
}

interface ImageGenerationProvider {
    suspend fun generate(
        request: ImageGenerationRequest,
        cancellation: GenerationCancellation = GenerationCancellation(),
    ): ImageGenerationResult
}

internal fun sanitizeProviderMessage(
    value: String,
    apiKey: ApiKey,
    requestPrompt: String,
    endpointHost: String,
): String? {
    val rawKey = apiKey.reveal()
    val sensitiveVariants = buildSet {
        add(rawKey)
        addAll(listOf(requestPrompt, endpointHost).filter(String::isNotBlank))
        add(URLEncoder.encode(rawKey, StandardCharsets.UTF_8.name()))
        HttpUrl.Builder()
            .scheme("https")
            .host("redaction.invalid")
            .addQueryParameter("key", rawKey)
            .build()
            .encodedQuery
            ?.substringAfter('=')
            ?.let(::add)
    }.sortedByDescending(String::length)
    val withoutUrls = HTTP_URL_PATTERN.replace(value, "[REDACTED_URL]")
    val redacted = sensitiveVariants.fold(withoutUrls) { sanitized, sensitiveValue ->
        sanitized.replace(sensitiveValue, "[REDACTED]", ignoreCase = true)
    }
    return redacted
        .map { character ->
            if (character.code < 0x20 || character.code in 0x7f..0x9f) ' ' else character
        }
        .joinToString(separator = "")
        .replace(WHITESPACE_PATTERN, " ")
        .trim()
        .take(MAX_PROVIDER_MESSAGE_LENGTH)
        .trimEnd()
        .ifBlank { null }
}

internal const val MAX_PROVIDER_MESSAGE_LENGTH = 200

private val HTTP_URL_PATTERN = Regex("(?i)\\bhttps?://[^\\s<>\\\"']+")
private val WHITESPACE_PATTERN = Regex("\\s+")
