package io.github.ayaseminami.gnbp.provider

import io.github.ayaseminami.gnbp.provider.transport.DeliveryCertainty
import io.github.ayaseminami.gnbp.provider.transport.HttpMethod
import io.github.ayaseminami.gnbp.provider.transport.ProfileId
import io.github.ayaseminami.gnbp.provider.transport.ProviderEndpoint
import io.github.ayaseminami.gnbp.provider.transport.ProviderHttpBody
import io.github.ayaseminami.gnbp.provider.transport.ProviderHttpCall
import io.github.ayaseminami.gnbp.provider.transport.ProviderHttpResult
import io.github.ayaseminami.gnbp.provider.transport.ProviderHttpTransport
import io.github.ayaseminami.gnbp.provider.transport.TransportBinding
import java.util.Base64
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GeminiProviderTest {
    @Test
    fun `generation sends the desktop-compatible Gemini contract`() = runTest {
        val imageBytes = "deterministic-image".encodeToByteArray()
        val transport = RecordingTransport(
            response = ProviderHttpResult.Response(
                statusCode = 200,
                body = fixture("gemini/generate-success.json")
                    .replace("PLACEHOLDER_BASE64_PNG", Base64.getEncoder().encodeToString(imageBytes))
                    .encodeToByteArray(),
            ),
        )
        val provider = GeminiProvider(transport, strictBinding(), ApiKey("gemini-secret"))

        val result = provider.generate(
            ImageGenerationRequest(
                model = "gemini-2.5-flash-image",
                prompt = "draw a lighthouse",
                parameters = GenerationParameters.Gemini(
                    aspectRatio = "16:9",
                    imageSize = "2K",
                    temperature = 0.7,
                ),
                referenceImages = listOf(
                    ReferenceImage(
                        bytes = "reference".encodeToByteArray(),
                        mimeType = "image/png",
                        displayName = "reference.png",
                    ),
                ),
            ),
        )

        assertTrue(result is ImageGenerationResult.Success)
        assertTrue(imageBytes.contentEquals((result as ImageGenerationResult.Success).image.bytes))
        val call = transport.calls.single()
        assertEquals(HttpMethod.Post, call.method)
        assertEquals(listOf("v1beta", "models", "gemini-2.5-flash-image:generateContent"), call.pathSegments)
        assertEquals("gemini-secret", call.queryParameters.single { it.name == "key" }.value.reveal())
        assertTrue(call.queryParameters.single { it.name == "key" }.isSensitive)
        assertFalse(call.toString().contains("gemini-secret"))
        assertFalse(call.toString().contains("draw a lighthouse"))
        assertFalse(call.binding.toString().contains("relay.example"))

        val payload = Json.parseToJsonElement((call.body as ProviderHttpBody.Json).value).jsonObject
        val safety = payload.getValue("safetySettings").jsonArray
        assertEquals(4, safety.size)
        assertTrue(safety.all { it.jsonObject.getValue("threshold").jsonPrimitive.content == "BLOCK_NONE" })
        val config = payload.getValue("generationConfig").jsonObject
        assertEquals("16:9", config.getValue("imageConfig").jsonObject.getValue("aspectRatio").jsonPrimitive.content)
        assertEquals("2K", config.getValue("imageConfig").jsonObject.getValue("imageSize").jsonPrimitive.content)
        val parts = payload.getValue("contents").jsonArray.single().jsonObject.getValue("parts").jsonArray
        assertEquals("draw a lighthouse", parts.first().jsonObject.getValue("text").jsonPrimitive.content)
        assertTrue(parts.any { "inlineData" in it.jsonObject })
        assertFalse(call.headers.any { it.name.equals("Authorization", ignoreCase = true) })
    }

    @Test
    fun `blocked Gemini response remains a typed provider error`() = runTest {
        val provider = GeminiProvider(
            transport = RecordingTransport(
                ProviderHttpResult.Response(200, fixture("gemini/generate-blocked.json").encodeToByteArray()),
            ),
            binding = strictBinding(),
            apiKey = ApiKey("gemini-secret"),
        )

        val result = provider.generate(
            ImageGenerationRequest(
                model = "model",
                prompt = "prompt",
                parameters = GenerationParameters.Gemini("1:1", "1K", 1.0),
            ),
        )

        assertEquals(ImageGenerationResult.Failure(ProviderError.Blocked("SAFETY")), result)
        assertEquals(
            DeliveryCertainty.Responded,
            (result as ImageGenerationResult.Failure).error.certainty,
        )
    }

    @Test
    fun `Gemini HTTP error redacts raw and URL-encoded API keys`() = runTest {
        val key = "secret+/="
        val provider = GeminiProvider(
            transport = RecordingTransport(
                ProviderHttpResult.Response(
                    401,
                    "rejected secret+/= and secret%2B%2F%3D".encodeToByteArray(),
                ),
            ),
            binding = strictBinding(),
            apiKey = ApiKey(key),
        )

        val result = provider.generate(geminiRequest())

        val message = ((result as ImageGenerationResult.Failure).error as ProviderError.HttpStatus)
            .providerMessage.orEmpty()
        assertFalse(message.contains(key))
        assertFalse(message.contains("secret%2B%2F%3D"))
        assertTrue(message.contains("[REDACTED]"))
    }

    @Test
    fun `malformed Gemini JSON remains a typed provider error`() = runTest {
        val provider = GeminiProvider(
            RecordingTransport(ProviderHttpResult.Response(200, "{".encodeToByteArray())),
            strictBinding(),
            ApiKey("key"),
        )

        val result = provider.generate(geminiRequest())

        val error = (result as ImageGenerationResult.Failure).error
        assertTrue(error is ProviderError.MalformedResponse)
        assertEquals(DeliveryCertainty.Responded, error.certainty)
    }

    private fun geminiRequest() = ImageGenerationRequest(
        model = "model",
        prompt = "prompt",
        parameters = GenerationParameters.Gemini("1:1", "1K", 1.0),
    )

    private fun strictBinding() = TransportBinding(
        profileId = ProfileId("gemini-profile"),
        endpoint = ProviderEndpoint.parse("https://relay.example/base"),
    )

    private fun fixture(path: String): String =
        checkNotNull(javaClass.classLoader?.getResource(path)) { "Missing fixture: $path" }.readText()
}

private class RecordingTransport(
    private val response: ProviderHttpResult,
) : ProviderHttpTransport {
    val calls = mutableListOf<ProviderHttpCall>()

    override suspend fun execute(call: ProviderHttpCall): ProviderHttpResult {
        calls += call
        return response
    }
}
