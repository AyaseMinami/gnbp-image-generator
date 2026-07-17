package io.github.ayaseminami.gnbp.provider.transport

import java.net.InetAddress
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import okhttp3.CertificatePinner
import okhttp3.Dns
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OkHttpProviderHttpTransportTest {
    @Test
    fun `acknowledged cleartext binding executes the exact endpoint request`() = runTest {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setResponseCode(200).setBody("response"))
            val transport = OkHttpProviderHttpTransport(LocalNetworkPermissionChecker { true })

            val result = transport.execute(call(cleartextBinding(server), listOf("v1", "generate")))

            assertEquals(
                ProviderHttpResult.Response(200, "response".encodeToByteArray()),
                result,
            )
            assertEquals("/base/v1/generate", server.takeRequest().path)
        }
    }

    @Test
    fun `redirect is returned without replaying the request`() = runTest {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(
                MockResponse()
                    .setResponseCode(307)
                    .setHeader("Location", server.url("/other")),
            )
            val transport = OkHttpProviderHttpTransport(LocalNetworkPermissionChecker { true })

            val result = transport.execute(call(cleartextBinding(server), listOf("v1", "generate")))

            assertEquals(ProviderHttpResult.Failure(TransportFailure.RedirectRejected(307)), result)
            assertEquals(1, server.requestCount)
        }
    }

    @Test
    fun `disconnect after request starts preserves possibly-sent certainty`() = runTest {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
            val transport = OkHttpProviderHttpTransport(LocalNetworkPermissionChecker { true })

            val result = transport.execute(call(cleartextBinding(server), listOf("v1", "generate")))

            assertTrue(result is ProviderHttpResult.Failure)
            val failure = (result as ProviderHttpResult.Failure).error
            assertEquals(DeliveryCertainty.PossiblySent, failure.certainty)
        }
    }

    @Test
    fun `strict TLS rejects a relay signed by an untrusted private CA`() = runTest {
        tlsServer().use { fixture ->
            fixture.server.enqueue(MockResponse().setBody("never trusted"))
            val transport = OkHttpProviderHttpTransport(LocalNetworkPermissionChecker { true })
            val binding = TransportBinding(
                profileId = ProfileId("strict-profile"),
                endpoint = ProviderEndpoint.parse(fixture.server.url("/base/").toString()),
            )

            val result = transport.execute(call(binding, listOf("v1", "generate")))

            assertTrue("result=$result", result is ProviderHttpResult.Failure)
            assertTrue("result=$result", (result as ProviderHttpResult.Failure).error is TransportFailure.Tls)
            assertEquals(DeliveryCertainty.NotSent, result.error.certainty)
        }
    }

    @Test
    fun `custom CA trust is scoped to the selected TLS binding`() = runTest {
        tlsServer().use { fixture ->
            fixture.server.enqueue(MockResponse().setBody("trusted"))
            val transport = OkHttpProviderHttpTransport(LocalNetworkPermissionChecker { true })
            val endpoint = ProviderEndpoint.parse(fixture.server.url("/base/").toString())
            val binding = TransportBinding(
                profileId = ProfileId("custom-ca-profile"),
                endpoint = endpoint,
                securityMode = TransportSecurityMode.CustomCaTls(
                    certificates = listOf(fixture.ca.certificate.encoded),
                ),
            )

            val result = transport.execute(call(binding, listOf("v1", "generate")))

            assertEquals(ProviderHttpResult.Response(200, "trusted".encodeToByteArray()), result)
        }
    }

    @Test
    fun `trust all TLS requires an acknowledgement for the exact binding`() = runTest {
        tlsServer().use { fixture ->
            fixture.server.enqueue(MockResponse().setBody("unsafe trusted"))
            val transport = OkHttpProviderHttpTransport(LocalNetworkPermissionChecker { true })
            val endpoint = ProviderEndpoint.parse(fixture.server.url("/base/").toString())
            val profileId = ProfileId("unsafe-profile")
            val binding = TransportBinding(
                profileId = profileId,
                endpoint = endpoint,
                securityMode = TransportSecurityMode.UnsafeTrustAllTls(
                    acknowledgement = UnsafeTransportAcknowledgement(
                        profileId = profileId,
                        authority = endpoint.authority,
                        mode = UnsafeTransportMode.TrustAllTls,
                        policyRevision = TransportBinding.CURRENT_POLICY_REVISION,
                        acceptedAtEpochMillis = 1L,
                    ),
                ),
            )

            val result = transport.execute(call(binding, listOf("v1", "generate")))

            assertEquals(ProviderHttpResult.Response(200, "unsafe trusted".encodeToByteArray()), result)
        }
    }

    @Test
    fun `pinned server certificate can explicitly replace hostname identity`() = runTest {
        tlsServer(serverHostname = "certificate-name.invalid").use { fixture ->
            fixture.server.enqueue(MockResponse().setBody("pinned"))
            val transport = OkHttpProviderHttpTransport(LocalNetworkPermissionChecker { true })
            val endpoint = ProviderEndpoint.parse(fixture.server.url("/base/").toString())
            val binding = TransportBinding(
                profileId = ProfileId("pinned-profile"),
                endpoint = endpoint,
                securityMode = TransportSecurityMode.PinnedServerCertificateTls(
                    certificate = fixture.serverCertificate.certificate.encoded,
                    allowHostnameMismatch = true,
                ),
            )

            val result = transport.execute(call(binding, listOf("v1", "generate")))

            assertEquals(ProviderHttpResult.Response(200, "pinned".encodeToByteArray()), result)
        }
    }

    @Test
    fun `timeout after request headers is possibly sent`() = runTest {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val transport = OkHttpProviderHttpTransport(
                localNetworkPermissionChecker = LocalNetworkPermissionChecker { true },
                callTimeoutMillis = 200,
            )

            val result = transport.execute(call(cleartextBinding(server), listOf("v1", "generate")))

            assertEquals(
                ProviderHttpResult.Failure(
                    TransportFailure.Network(
                        NetworkFailureReason.Timeout,
                        DeliveryCertainty.PossiblySent,
                    ),
                ),
                result,
            )
        }
    }

    @Test
    fun `explicit cancellation preserves request delivery certainty`() = runTest {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val cancellation = TransportCancellation()
            val transport = OkHttpProviderHttpTransport(LocalNetworkPermissionChecker { true })
            val pending = async(start = CoroutineStart.UNDISPATCHED) {
                transport.execute(
                    call(cleartextBinding(server), listOf("v1", "generate")).copy(
                        cancellation = cancellation,
                    ),
                )
            }
            checkNotNull(server.takeRequest(2, TimeUnit.SECONDS))

            cancellation.cancel()

            assertEquals(
                ProviderHttpResult.Failure(
                    TransportFailure.Cancelled(DeliveryCertainty.PossiblySent),
                ),
                pending.await(),
            )
        }
    }

    @Test
    fun `wrong SPKI pin is a typed pin mismatch`() = runTest {
        tlsServer().use { fixture ->
            val unrelated = HeldCertificate.Builder().commonName("unrelated").build()
            fixture.server.enqueue(MockResponse().setBody("not returned"))
            val endpoint = ProviderEndpoint.parse(fixture.server.url("/base/").toString())
            val binding = TransportBinding(
                profileId = ProfileId("pinned-ca-profile"),
                endpoint = endpoint,
                securityMode = TransportSecurityMode.CustomCaTls(
                    certificates = listOf(fixture.ca.certificate.encoded),
                    spkiPins = setOf(CertificatePinner.pin(unrelated.certificate)),
                ),
            )

            val result = OkHttpProviderHttpTransport(LocalNetworkPermissionChecker { true })
                .execute(call(binding, listOf("v1", "generate")))

            assertEquals(
                ProviderHttpResult.Failure(
                    TransportFailure.Tls(TlsFailureReason.PinMismatch, DeliveryCertainty.NotSent),
                ),
                result,
            )
        }
    }

    @Test
    fun `LAN DNS result is rejected before socket use when profile has not opted in`() = runTest {
        val binding = cleartextBinding("http://relay.invalid")
        val transport = OkHttpProviderHttpTransport(
            localNetworkPermissionChecker = LocalNetworkPermissionChecker { true },
            baseDns = Dns { listOf(InetAddress.getByName("192.168.10.2")) },
        )

        val result = transport.execute(call(binding, listOf("v1", "generate")))

        assertEquals(
            ProviderHttpResult.Failure(
                TransportFailure.Network(
                    NetworkFailureReason.LocalNetworkDisabled,
                    DeliveryCertainty.NotSent,
                ),
            ),
            result,
        )
    }

    @Test
    fun `LAN profile requires the Android runtime permission before socket use`() = runTest {
        val binding = cleartextBinding("http://relay.invalid").copy(
            localNetworkMode = LocalNetworkMode.AllowLan,
        )
        val transport = OkHttpProviderHttpTransport(
            localNetworkPermissionChecker = LocalNetworkPermissionChecker { false },
            baseDns = Dns { listOf(InetAddress.getByName("192.168.10.2")) },
        )

        val result = transport.execute(call(binding, listOf("v1", "generate")))

        assertEquals(
            ProviderHttpResult.Failure(
                TransportFailure.Network(
                    NetworkFailureReason.LocalNetworkPermissionRequired,
                    DeliveryCertainty.NotSent,
                ),
            ),
            result,
        )
    }

    @Test
    fun `invalid custom CA is a typed not-sent configuration failure`() = runTest {
        val binding = TransportBinding(
            profileId = ProfileId("invalid-ca-profile"),
            endpoint = ProviderEndpoint.parse("https://relay.invalid"),
            securityMode = TransportSecurityMode.CustomCaTls(
                certificates = listOf("not-a-certificate".encodeToByteArray()),
            ),
        )

        val result = OkHttpProviderHttpTransport(LocalNetworkPermissionChecker { true })
            .execute(call(binding, listOf("v1", "generate")))

        assertEquals(
            ProviderHttpResult.Failure(
                TransportFailure.InvalidRequest("Invalid transport security configuration"),
            ),
            result,
        )
    }

    @Test
    fun `oversized response is rejected with responded certainty`() = runTest {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setBody("123456"))
            val transport = OkHttpProviderHttpTransport(
                localNetworkPermissionChecker = LocalNetworkPermissionChecker { true },
                maxResponseBytes = 5,
            )

            val result = transport.execute(call(cleartextBinding(server), listOf("v1", "generate")))

            assertEquals(
                ProviderHttpResult.Failure(
                    TransportFailure.Network(
                        NetworkFailureReason.ResponseTooLarge,
                        DeliveryCertainty.Responded,
                    ),
                ),
                result,
            )
        }
    }

    private fun call(
        binding: TransportBinding,
        path: List<String>,
    ) = ProviderHttpCall(
        binding = binding,
        method = HttpMethod.Post,
        pathSegments = path,
        body = ProviderHttpBody.Json("{}"),
    )

    private fun cleartextBinding(server: MockWebServer): TransportBinding {
        return cleartextBinding(server.url("/base/").toString())
    }

    private fun cleartextBinding(url: String): TransportBinding {
        val endpoint = ProviderEndpoint.parse(url)
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

    private fun tlsServer(serverHostname: String = "localhost"): TlsServerFixture {
        val ca = HeldCertificate.Builder()
            .certificateAuthority(0)
            .commonName("GNBP test CA")
            .build()
        val serverCertificate = HeldCertificate.Builder()
            .commonName(serverHostname)
            .addSubjectAlternativeName(serverHostname)
            .signedBy(ca)
            .build()
        val serverCertificates = HandshakeCertificates.Builder()
            .heldCertificate(serverCertificate, ca.certificate)
            .build()
        val server = MockWebServer()
        server.useHttps(serverCertificates.sslSocketFactory(), false)
        server.start()
        return TlsServerFixture(server, ca, serverCertificate)
    }
}

private data class TlsServerFixture(
    val server: MockWebServer,
    val ca: HeldCertificate,
    val serverCertificate: HeldCertificate,
) : AutoCloseable {
    override fun close() {
        server.close()
    }
}
