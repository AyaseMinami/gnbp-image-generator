package io.github.ayaseminami.gnbp.provider

import io.github.ayaseminami.gnbp.provider.transport.DeliveryCertainty
import io.github.ayaseminami.gnbp.provider.transport.HttpMethod
import io.github.ayaseminami.gnbp.provider.transport.LocalNetworkPermissionChecker
import io.github.ayaseminami.gnbp.provider.transport.OkHttpProviderHttpTransport
import io.github.ayaseminami.gnbp.provider.transport.ProfileId
import io.github.ayaseminami.gnbp.provider.transport.ProviderEndpoint
import io.github.ayaseminami.gnbp.provider.transport.ProviderHttpBody
import io.github.ayaseminami.gnbp.provider.transport.ProviderHttpCall
import io.github.ayaseminami.gnbp.provider.transport.ProviderHttpResult
import io.github.ayaseminami.gnbp.provider.transport.ProviderHttpTransport
import io.github.ayaseminami.gnbp.provider.transport.RequestOutcomeUnknownReason
import io.github.ayaseminami.gnbp.provider.transport.TransportBinding
import io.github.ayaseminami.gnbp.provider.transport.TransportFailure
import io.github.ayaseminami.gnbp.provider.transport.TransportSecurityMode
import io.github.ayaseminami.gnbp.provider.transport.UnsafeTransportAcknowledgement
import io.github.ayaseminami.gnbp.provider.transport.UnsafeTransportMode
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GeminiProviderTest {
    @Test
    fun `Gemini fixture completes through the production HTTP transport`() = runTest {
        MockWebServer().use { server ->
            server.start()
            val imageBytes = "transport-image".encodeToByteArray()
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setBody(
                        fixture("gemini/generate-success.json")
                            .replace(
                                "PLACEHOLDER_BASE64_PNG",
                                Base64.getEncoder().encodeToString(imageBytes),
                            ),
                    ),
            )
            val provider = GeminiProvider(
                transport = OkHttpProviderHttpTransport(LocalNetworkPermissionChecker { true }),
                binding = cleartextBinding(server),
                apiKey = ApiKey("gemini transport key"),
            )

            val result = provider.generate(geminiRequest())

            assertTrue(imageBytes.contentEquals((result as ImageGenerationResult.Success).image.bytes))
            val recorded = server.takeRequest()
            assertEquals(
                "/base/v1beta/models/model:generateContent",
                recorded.requestUrl?.encodedPath,
            )
            assertEquals("gemini transport key", recorded.requestUrl?.queryParameter("key"))
            assertFalse(recorded.body.readUtf8().contains("gemini transport key"))
        }
    }

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
    fun `Gemini HTTP diagnostic is structured bounded and fully sanitized`() = runTest {
        val key = "secret key+/="
        val prompt = "private prompt sentinel"
        val provider = GeminiProvider(
            transport = RecordingTransport(
                ProviderHttpResult.Response(
                    524,
                    (
                        "{\"error\":{\"message\":\"relay timed out\\n\\u0007 " +
                            "secret key+/= secret+key%2B%2F%3D secret%20key%2B%2F%3D " +
                            "https://relay.example/private $prompt ${"x".repeat(240)}\"}}"
                    ).encodeToByteArray(),
                ),
            ),
            binding = strictBinding(),
            apiKey = ApiKey(key),
        )

        val result = provider.generate(geminiRequest().copy(prompt = prompt))

        val error = (result as ImageGenerationResult.Failure).error as ProviderError.HttpStatus
        val message = requireNotNull(error.providerMessage)
        assertEquals(524, error.statusCode)
        assertTrue(message.length <= 200)
        assertFalse(message.any(Char::isUnsafeProviderMessageCharacter))
        assertFalse(message.contains(key))
        assertFalse(message.contains("secret+key%2B%2F%3D"))
        assertFalse(message.contains("secret%20key%2B%2F%3D"))
        assertFalse(message.contains("relay.example"))
        assertFalse(message.contains(prompt))
        assertTrue(message.contains("[REDACTED]"))
        assertFalse(error.toString().contains("relay timed out"))
    }

    @Test
    fun `Gemini HTTP diagnostic redacts a private URL extending the prompt`() = runTest {
        val prompt = "https://relay.internal.example"
        val provider = GeminiProvider(
            transport = RecordingTransport(
                ProviderHttpResult.Response(
                    400,
                    (
                        "{\"error\":{\"message\":\"blocked at " +
                            "$prompt/v1/private/images\"}}"
                    ).encodeToByteArray(),
                ),
            ),
            binding = strictBinding(),
            apiKey = ApiKey("key"),
        )

        val result = provider.generate(geminiRequest().copy(prompt = prompt))

        val error = (result as ImageGenerationResult.Failure).error as ProviderError.HttpStatus
        assertEquals("blocked at [REDACTED]", error.providerMessage)
    }

    @Test
    fun `Gemini HTTP error never falls back to an unstructured response body`() = runTest {
        val provider = GeminiProvider(
            transport = RecordingTransport(
                ProviderHttpResult.Response(502, "raw secret response sentinel".encodeToByteArray()),
            ),
            binding = strictBinding(),
            apiKey = ApiKey("key"),
        )

        val result = provider.generate(geminiRequest())

        assertEquals(
            ProviderError.HttpStatus(502, null),
            (result as ImageGenerationResult.Failure).error,
        )
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

    @Test
    fun `caller can cancel a transmitted provider request without making it retryable`() = runTest {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val cancellation = GenerationCancellation()
            val provider = GeminiProvider(
                transport = OkHttpProviderHttpTransport(LocalNetworkPermissionChecker { true }),
                binding = cleartextBinding(server),
                apiKey = ApiKey("gemini-secret"),
            )
            val pending = async(start = CoroutineStart.UNDISPATCHED) {
                provider.generate(geminiRequest(), cancellation)
            }
            checkNotNull(server.takeRequest(2, TimeUnit.SECONDS))

            cancellation.cancel()

            assertEquals(
                ImageGenerationResult.Failure(
                    ProviderError.Transport(
                        TransportFailure.RequestOutcomeUnknown(
                            RequestOutcomeUnknownReason.Cancelled,
                        ),
                    ),
                ),
                pending.await(),
            )
        }
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

    private fun cleartextBinding(server: MockWebServer): TransportBinding {
        val endpoint = ProviderEndpoint.parse(server.url("/base/").toString())
        val profileId = ProfileId("cleartext-profile")
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
