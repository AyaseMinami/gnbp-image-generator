package io.github.ayaseminami.gnbp.provider

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
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenAiProviderTest {
    @Test
    fun `generation without references sends the JSON generations contract`() = runTest {
        val imageBytes = "openai-image".encodeToByteArray()
        val transport = OpenAiRecordingTransport(successResponse(imageBytes))
        val provider = OpenAiProvider(transport, strictBinding(), ApiKey("openai-secret"))

        val result = provider.generate(request())

        assertTrue(result is ImageGenerationResult.Success)
        assertTrue(imageBytes.contentEquals((result as ImageGenerationResult.Success).image.bytes))
        val call = transport.calls.single()
        assertEquals(HttpMethod.Post, call.method)
        assertEquals(listOf("v1", "images", "generations"), call.pathSegments)
        assertEquals("Bearer openai-secret", call.headers.single { it.name == "Authorization" }.value.reveal())
        assertTrue(call.headers.single { it.name == "Authorization" }.isSensitive)
        val json = Json.parseToJsonElement((call.body as ProviderHttpBody.Json).value).jsonObject
        assertEquals("gpt-image-1", json.getValue("model").jsonPrimitive.content)
        assertEquals("draw a harbor", json.getValue("prompt").jsonPrimitive.content)
        assertEquals("1536x1024", json.getValue("size").jsonPrimitive.content)
        assertEquals("high", json.getValue("quality").jsonPrimitive.content)
        assertEquals(1, json.getValue("n").jsonPrimitive.content.toInt())
    }

    @Test
    fun `generation with references sends the multipart edits contract`() = runTest {
        val transport = OpenAiRecordingTransport(successResponse("edited".encodeToByteArray()))
        val provider = OpenAiProvider(transport, strictBinding(), ApiKey("openai-secret"))
        val request = request().copy(
            referenceImages = listOf(
                ReferenceImage("first".encodeToByteArray(), "image/png", "first.png"),
                ReferenceImage("second".encodeToByteArray(), "image/jpeg", "second.jpg"),
            ),
        )

        provider.generate(request)

        val call = transport.calls.single()
        assertEquals(listOf("v1", "images", "edits"), call.pathSegments)
        val multipart = call.body as ProviderHttpBody.Multipart
        assertEquals(listOf("model", "prompt", "n", "size", "quality"), multipart.fields.map { it.name })
        assertEquals(listOf("image", "image"), multipart.files.map { it.name })
        assertEquals(listOf("first.png", "second.jpg"), multipart.files.map { it.fileName })
        assertTrue(multipart.files[0].bytes.contentEquals("first".encodeToByteArray()))
        assertTrue(multipart.files[1].bytes.contentEquals("second".encodeToByteArray()))
    }

    @Test
    fun `OpenAI-compatible response accepts object-shaped data`() = runTest {
        val bytes = "object-image".encodeToByteArray()
        val response = """{"data":{"b64_json":"${Base64.getEncoder().encodeToString(bytes)}"}}"""
        val provider = OpenAiProvider(
            OpenAiRecordingTransport(ProviderHttpResult.Response(200, response.encodeToByteArray())),
            strictBinding(),
            ApiKey("key"),
        )

        val result = provider.generate(request())

        assertTrue(bytes.contentEquals((result as ImageGenerationResult.Success).image.bytes))
    }

    @Test
    fun `OpenAI-compatible HTTP error uses the structured provider message`() = runTest {
        val provider = OpenAiProvider(
            OpenAiRecordingTransport(
                ProviderHttpResult.Response(400, fixture("openai/error.json").encodeToByteArray()),
            ),
            strictBinding(),
            ApiKey("key"),
        )

        val result = provider.generate(request())

        assertEquals(
            ImageGenerationResult.Failure(
                ProviderError.HttpStatus(400, "Redacted provider error"),
            ),
            result,
        )
    }

    private fun request() = ImageGenerationRequest(
        model = "gpt-image-1",
        prompt = "draw a harbor",
        parameters = GenerationParameters.OpenAi(size = "1536x1024", quality = "high"),
    )

    private fun strictBinding() = TransportBinding(
        profileId = ProfileId("openai-profile"),
        endpoint = ProviderEndpoint.parse("https://relay.example/api"),
    )

    private fun successResponse(bytes: ByteArray): ProviderHttpResult.Response =
        ProviderHttpResult.Response(
            statusCode = 200,
            body = fixture("openai/generation-success.json")
                .replace("PLACEHOLDER_BASE64_PNG", Base64.getEncoder().encodeToString(bytes))
                .encodeToByteArray(),
        )

    private fun fixture(path: String): String =
        checkNotNull(javaClass.classLoader?.getResource(path)) { "Missing fixture: $path" }.readText()
}

private class OpenAiRecordingTransport(
    private val response: ProviderHttpResult,
) : ProviderHttpTransport {
    val calls = mutableListOf<ProviderHttpCall>()

    override suspend fun execute(call: ProviderHttpCall): ProviderHttpResult {
        calls += call
        return response
    }
}
