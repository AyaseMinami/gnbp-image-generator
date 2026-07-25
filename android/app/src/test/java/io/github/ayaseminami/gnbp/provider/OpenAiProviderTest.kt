package io.github.ayaseminami.gnbp.provider

import io.github.ayaseminami.gnbp.provider.transport.HttpMethod
import io.github.ayaseminami.gnbp.provider.transport.LocalNetworkPermissionChecker
import io.github.ayaseminami.gnbp.provider.transport.OkHttpProviderHttpTransport
import io.github.ayaseminami.gnbp.provider.transport.ProfileId
import io.github.ayaseminami.gnbp.provider.transport.ProviderEndpoint
import io.github.ayaseminami.gnbp.provider.transport.ProviderHttpBody
import io.github.ayaseminami.gnbp.provider.transport.ProviderHttpCall
import io.github.ayaseminami.gnbp.provider.transport.ProviderHttpResult
import io.github.ayaseminami.gnbp.provider.transport.ProviderHttpTransport
import io.github.ayaseminami.gnbp.provider.transport.TransportBinding
import io.github.ayaseminami.gnbp.provider.transport.TransportSecurityMode
import io.github.ayaseminami.gnbp.provider.transport.UnsafeTransportAcknowledgement
import io.github.ayaseminami.gnbp.provider.transport.UnsafeTransportMode
import java.util.Base64
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenAiProviderTest {
    @Test
    fun `OpenAI generation fixture completes through the production HTTP transport`() = runTest {
        MockWebServer().use { server ->
            server.start()
            val imageBytes = "transport-generation".encodeToByteArray()
            server.enqueue(MockResponse().setBody(successFixture(imageBytes)))
            val provider = OpenAiProvider(
                transport = OkHttpProviderHttpTransport(LocalNetworkPermissionChecker { true }),
                binding = cleartextBinding(server),
                apiKey = ApiKey("openai-transport-key"),
            )

            val result = provider.generate(request())

            assertTrue(imageBytes.contentEquals((result as ImageGenerationResult.Success).image.bytes))
            val recorded = server.takeRequest()
            assertEquals("/api/v1/images/generations", recorded.requestUrl?.encodedPath)
            assertEquals("Bearer openai-transport-key", recorded.headers["Authorization"])
            assertEquals("application/json; charset=utf-8", recorded.headers["Content-Type"])
        }
    }

    @Test
    fun `OpenAI edit multipart completes through the production HTTP transport`() = runTest {
        MockWebServer().use { server ->
            server.start()
            val imageBytes = "transport-edit".encodeToByteArray()
            server.enqueue(MockResponse().setBody(successFixture(imageBytes)))
            val provider = OpenAiProvider(
                transport = OkHttpProviderHttpTransport(LocalNetworkPermissionChecker { true }),
                binding = cleartextBinding(server),
                apiKey = ApiKey("openai-transport-key"),
            )
            val editRequest = request().copy(
                referenceImages = listOf(
                    ReferenceImage("reference".encodeToByteArray(), "image/png", "reference.png"),
                ),
            )

            val result = provider.generate(editRequest)

            assertTrue(imageBytes.contentEquals((result as ImageGenerationResult.Success).image.bytes))
            val recorded = server.takeRequest()
            assertEquals("/api/v1/images/edits", recorded.requestUrl?.encodedPath)
            assertTrue(recorded.headers["Content-Type"].orEmpty().startsWith("multipart/form-data; boundary="))
            val body = recorded.body.readUtf8()
            assertTrue(body.contains("name=\"image\"; filename=\"reference.png\""))
            assertTrue(body.contains("reference"))
        }
    }

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

    @Test
    fun `OpenAI-compatible HTTP diagnostic is bounded and fully sanitized`() = runTest {
        val key = "openai secret key+/="
        val prompt = "private OpenAI prompt sentinel"
        val provider = OpenAiProvider(
            OpenAiRecordingTransport(
                ProviderHttpResult.Response(
                    524,
                    (
                        "{\"error\":{\"message\":\"relay timed out\\n\\u0007 " +
                            "openai secret key+/= openai+secret+key%2B%2F%3D " +
                            "openai%20secret%20key%2B%2F%3D " +
                            "https://relay.example/private $prompt ${"x".repeat(240)}\"}}"
                    ).encodeToByteArray(),
                ),
            ),
            strictBinding(),
            ApiKey(key),
        )

        val result = provider.generate(request().copy(prompt = prompt))

        val error = (result as ImageGenerationResult.Failure).error as ProviderError.HttpStatus
        val message = requireNotNull(error.providerMessage)
        assertEquals(524, error.statusCode)
        assertTrue(message.length <= 200)
        assertFalse(message.any(Char::isUnsafeProviderMessageCharacter))
        assertFalse(message.contains(key))
        assertFalse(message.contains("openai+secret+key%2B%2F%3D"))
        assertFalse(message.contains("openai%20secret%20key%2B%2F%3D"))
        assertFalse(message.contains("relay.example"))
        assertFalse(message.contains(prompt))
    }

    @Test
    fun `OpenAI-compatible HTTP diagnostic redacts a prompt containing a URL`() = runTest {
        val prompt = "a photo of https://example.com/logo.png in the style of Van Gogh"
        val provider = OpenAiProvider(
            OpenAiRecordingTransport(
                ProviderHttpResult.Response(
                    400,
                    (
                        "{\"error\":{\"message\":\"Your prompt was rejected: " +
                            "$prompt -- please revise.\"}}"
                    ).encodeToByteArray(),
                ),
            ),
            strictBinding(),
            ApiKey("key"),
        )

        val result = provider.generate(request().copy(prompt = prompt))

        val error = (result as ImageGenerationResult.Failure).error as ProviderError.HttpStatus
        assertEquals(
            "Your prompt was rejected: [REDACTED] -- please revise.",
            error.providerMessage,
        )
    }

    @Test
    fun `OpenAI-compatible HTTP diagnostic neutralizes Unicode formatting characters`() = runTest {
        val provider = OpenAiProvider(
            OpenAiRecordingTransport(
                ProviderHttpResult.Response(
                    400,
                    "{\"error\":{\"message\":\"safe\u202Eevil\u2028next\u00A0word\u200Bhidden\"}}"
                        .encodeToByteArray(),
                ),
            ),
            strictBinding(),
            ApiKey("key"),
        )

        val result = provider.generate(request())

        val error = (result as ImageGenerationResult.Failure).error as ProviderError.HttpStatus
        assertEquals("safe evil next word hidden", error.providerMessage)
    }

    @Test
    fun `OpenAI-compatible HTTP diagnostic does not split a surrogate pair at its limit`() = runTest {
        val prefix = "x".repeat(199)
        val provider = OpenAiProvider(
            OpenAiRecordingTransport(
                ProviderHttpResult.Response(
                    400,
                    "{\"error\":{\"message\":\"$prefix\uD83D\uDE00\"}}".encodeToByteArray(),
                ),
            ),
            strictBinding(),
            ApiKey("key"),
        )

        val result = provider.generate(request())

        val error = (result as ImageGenerationResult.Failure).error as ProviderError.HttpStatus
        assertEquals(prefix, error.providerMessage)
    }

    @Test
    fun `OpenAI-compatible HTTP error never exposes an unstructured response body`() = runTest {
        val provider = OpenAiProvider(
            OpenAiRecordingTransport(
                ProviderHttpResult.Response(524, "raw secret response sentinel".encodeToByteArray()),
            ),
            strictBinding(),
            ApiKey("key"),
        )

        val result = provider.generate(request())

        assertEquals(
            ImageGenerationResult.Failure(ProviderError.HttpStatus(524, null)),
            result,
        )
    }

    @Test
    fun `malformed OpenAI JSON remains a typed provider error`() = runTest {
        val provider = OpenAiProvider(
            OpenAiRecordingTransport(ProviderHttpResult.Response(200, "{".encodeToByteArray())),
            strictBinding(),
            ApiKey("key"),
        )

        val result = provider.generate(request())

        assertTrue((result as ImageGenerationResult.Failure).error is ProviderError.MalformedResponse)
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

    private fun cleartextBinding(server: MockWebServer): TransportBinding {
        val endpoint = ProviderEndpoint.parse(server.url("/api/").toString())
        val profileId = ProfileId("openai-cleartext-profile")
        return TransportBinding(
            profileId = profileId,
            endpoint = endpoint,
            securityMode = TransportSecurityMode.CleartextHttp(
                UnsafeTransportAcknowledgement(
                    profileId = profileId,
                    authority = endpoint.authority,
                    mode = UnsafeTransportMode.CleartextHttp,
                    policyRevision = TransportBinding.CURRENT_POLICY_REVISION,
                    acceptedAtEpochMillis = 1L,
                ),
            ),
        )
    }

    private fun successResponse(bytes: ByteArray): ProviderHttpResult.Response =
        ProviderHttpResult.Response(
            statusCode = 200,
            body = successFixture(bytes).encodeToByteArray(),
        )

    private fun successFixture(bytes: ByteArray): String =
        fixture("openai/generation-success.json")
            .replace("PLACEHOLDER_BASE64_PNG", Base64.getEncoder().encodeToString(bytes))

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
