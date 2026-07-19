package io.github.ayaseminami.gnbp.provider

import io.github.ayaseminami.gnbp.provider.transport.DeliveryCertainty
import io.github.ayaseminami.gnbp.provider.transport.LocalNetworkPermissionChecker
import io.github.ayaseminami.gnbp.provider.transport.OkHttpProviderHttpTransport
import io.github.ayaseminami.gnbp.provider.transport.ProfileId
import io.github.ayaseminami.gnbp.provider.transport.ProviderEndpoint
import io.github.ayaseminami.gnbp.provider.transport.TransportBinding
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ProviderSmokeTest {
    @Test
    fun `configured provider returns an image`() = runBlocking {
        val config = SmokeConfig.load()
        val binding = runCatching {
            TransportBinding(
                profileId = ProfileId("manual-provider-smoke"),
                endpoint = ProviderEndpoint.parse(config.endpoint),
            )
        }.getOrElse { throw AssertionError("Provider smoke endpoint is invalid") }
        val transport = OkHttpProviderHttpTransport(
            localNetworkPermissionChecker = LocalNetworkPermissionChecker { true },
        )
        val provider = when (config.providerKind) {
            SmokeProviderKind.Gemini -> GeminiProvider(transport, binding, ApiKey(config.apiKey))
            SmokeProviderKind.OpenAiCompatible -> OpenAiProvider(transport, binding, ApiKey(config.apiKey))
        }
        val parameters = when (config.providerKind) {
            SmokeProviderKind.Gemini -> GenerationParameters.Gemini(
                aspectRatio = "1:1",
                imageSize = "1K",
                temperature = 0.9,
            )
            SmokeProviderKind.OpenAiCompatible -> GenerationParameters.OpenAi(
                size = "auto",
                quality = "auto",
            )
        }

        when (
            val result = provider.generate(
                ImageGenerationRequest(
                    model = config.model,
                    prompt = "A simple red circle centered on a plain white background.",
                    parameters = parameters,
                ),
            )
        ) {
            is ImageGenerationResult.Success -> {
                assertTrue("Provider returned an empty image", result.image.bytes.isNotEmpty())
                assertTrue("Provider returned a non-image MIME type", result.image.mimeType.startsWith("image/"))
            }
            is ImageGenerationResult.Failure -> fail(result.error.safeSummary())
        }
    }
}

private enum class SmokeProviderKind {
    Gemini,
    OpenAiCompatible,
}

private data class SmokeConfig(
    val providerKind: SmokeProviderKind,
    val endpoint: String,
    val apiKey: String,
    val model: String,
) {
    companion object {
        fun load(): SmokeConfig {
            val configPath = System.getProperty("gnbp.providerSmoke.config")
                ?: throw AssertionError("Provider smoke config path is missing")
            val raw = File(configPath).readText(Charsets.UTF_8).trim()
            val document = if (raw.startsWith('{')) raw else "{$raw}"
            val root = try {
                Json.parseToJsonElement(document).jsonObject
            } catch (_: Exception) {
                throw AssertionError("Provider smoke config is invalid JSON")
            }
            return SmokeConfig(
                providerKind = root.requiredProviderKind(),
                endpoint = root.requiredString("api_url"),
                apiKey = root.requiredString("api_key"),
                model = root.requiredString("model"),
            )
        }

        private fun JsonObject.requiredProviderKind(): SmokeProviderKind =
            when (requiredString("api_type").lowercase()) {
                "gemini", "google" -> SmokeProviderKind.Gemini
                "gpt", "openai", "openai-compatible", "openai_compatible" ->
                    SmokeProviderKind.OpenAiCompatible
                else -> throw AssertionError("Provider smoke api_type is unsupported")
            }

        private fun JsonObject.requiredString(name: String): String {
            val value = runCatching { getValue(name).jsonPrimitive.content.trim() }.getOrNull()
            if (value.isNullOrEmpty()) throw AssertionError("Provider smoke field '$name' is missing")
            return value
        }
    }
}

private fun ProviderError.safeSummary(): String = when (this) {
    is ProviderError.InvalidRequest -> "Provider smoke failed: invalid request"
    is ProviderError.Blocked -> "Provider smoke failed: content blocked"
    is ProviderError.HttpStatus -> "Provider smoke failed: HTTP $statusCode"
    is ProviderError.Transport ->
        "Provider smoke failed: transport ${failure::class.simpleName}, certainty=${certainty.safeName()}"
    is ProviderError.MalformedResponse -> "Provider smoke failed: malformed response"
    ProviderError.NoImageData -> "Provider smoke failed: no image data"
}

private fun DeliveryCertainty.safeName(): String = this::class.simpleName ?: "Unknown"
