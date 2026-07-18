package io.github.ayaseminami.gnbp.provider.transport

import java.net.InetAddress
import java.net.UnknownHostException
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
import org.junit.Assert.assertFalse
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
    fun `unchanged binding reuses its policy-scoped HTTP client`() = runTest {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setBody("first"))
            server.enqueue(MockResponse().setBody("second"))
            val transport = OkHttpProviderHttpTransport(LocalNetworkPermissionChecker { true })
            val binding = cleartextBinding(server)

            val first = transport.execute(call(binding, listOf("v1", "generate")))
            val second = transport.execute(call(binding, listOf("v1", "generate")))

            assertEquals(ProviderHttpResult.Response(200, "first".encodeToByteArray()), first)
            assertEquals(ProviderHttpResult.Response(200, "second".encodeToByteArray()), second)
            assertEquals(0, server.takeRequest().sequenceNumber)
            assertEquals(1, server.takeRequest().sequenceNumber)
        }
    }

    @Test
    fun `strict HTTP binding is rejected before DNS`() = runTest {
        var dnsUsed = false
        val binding = TransportBinding(
            profileId = ProfileId("strict-http-profile"),
            endpoint = ProviderEndpoint.parse("http://relay.invalid/base/"),
            securityMode = TransportSecurityMode.VerifiedTls,
        )
        val transport = OkHttpProviderHttpTransport(
            localNetworkPermissionChecker = LocalNetworkPermissionChecker { true },
            baseDns = Dns {
                dnsUsed = true
                listOf(InetAddress.getLoopbackAddress())
            },
        )

        val result = transport.execute(call(binding, listOf("v1", "generate")))

        assertEquals(
            ProviderHttpResult.Failure(TransportFailure.CleartextRejected),
            result,
        )
        assertFalse(dnsUsed)
    }

    @Test
    fun `invalid sensitive request values return a fixed sanitized failure`() = runTest {
        MockWebServer().use { server ->
            server.start()
            val sentinel = "sentinel-secret\n"
            val invalidCall = call(cleartextBinding(server), listOf("v1", "generate")).copy(
                headers = listOf(
                    HttpHeader(
                        name = "Authorization",
                        value = SensitiveValue(sentinel),
                        isSensitive = true,
                    ),
                ),
            )

            val result = OkHttpProviderHttpTransport(LocalNetworkPermissionChecker { true })
                .execute(invalidCall)

            assertEquals(
                ProviderHttpResult.Failure(
                    TransportFailure.InvalidRequest("Invalid transport request"),
                ),
                result,
            )
            assertFalse(result.toString().contains("sentinel-secret"))
            assertEquals(0, server.requestCount)
        }
    }

    @Test
    fun `unsupported acknowledgement policy revision is rejected before DNS`() = runTest {
        var dnsUsed = false
        val endpoint = ProviderEndpoint.parse("http://relay.invalid/base/")
        val profileId = ProfileId("stale-policy-profile")
        val unsupportedRevision = TransportBinding.CURRENT_POLICY_REVISION + 1
        val binding = TransportBinding(
            profileId = profileId,
            endpoint = endpoint,
            securityMode = TransportSecurityMode.CleartextHttp(
                UnsafeTransportAcknowledgement(
                    profileId = profileId,
                    authority = endpoint.authority,
                    mode = UnsafeTransportMode.CleartextHttp,
                    policyRevision = unsupportedRevision,
                    acceptedAtEpochMillis = 1L,
                ),
            ),
            policyRevision = unsupportedRevision,
        )
        val transport = OkHttpProviderHttpTransport(
            localNetworkPermissionChecker = LocalNetworkPermissionChecker { true },
            baseDns = Dns {
                dnsUsed = true
                listOf(InetAddress.getLoopbackAddress())
            },
        )

        val result = transport.execute(call(binding, listOf("v1", "generate")))

        assertEquals(
            ProviderHttpResult.Failure(TransportFailure.UnsafeAcknowledgementStale),
            result,
        )
        assertFalse(dnsUsed)
    }

    @Test
    fun `unsafe acknowledgement for another authority is a typed binding mismatch`() = runTest {
        var dnsUsed = false
        val endpoint = ProviderEndpoint.parse("http://relay.invalid/base/")
        val profileId = ProfileId("mismatched-authority-profile")
        val binding = TransportBinding(
            profileId = profileId,
            endpoint = endpoint,
            securityMode = TransportSecurityMode.CleartextHttp(
                UnsafeTransportAcknowledgement(
                    profileId = profileId,
                    authority = EndpointAuthority("http", "other.invalid", 80),
                    mode = UnsafeTransportMode.CleartextHttp,
                    policyRevision = TransportBinding.CURRENT_POLICY_REVISION,
                    acceptedAtEpochMillis = 1L,
                ),
            ),
        )
        val transport = OkHttpProviderHttpTransport(
            localNetworkPermissionChecker = LocalNetworkPermissionChecker { true },
            baseDns = Dns {
                dnsUsed = true
                listOf(InetAddress.getLoopbackAddress())
            },
        )

        val result = transport.execute(call(binding, listOf("v1", "generate")))

        assertEquals(
            ProviderHttpResult.Failure(TransportFailure.BindingMismatch),
            result,
        )
        assertFalse(dnsUsed)
    }

    @Test
    fun `unsafe transport without acknowledgement is rejected before DNS`() = runTest {
        var dnsUsed = false
        val binding = TransportBinding(
            profileId = ProfileId("missing-acknowledgement-profile"),
            endpoint = ProviderEndpoint.parse("http://relay.invalid/base/"),
            securityMode = TransportSecurityMode.CleartextHttp(acknowledgement = null),
        )
        val transport = OkHttpProviderHttpTransport(
            localNetworkPermissionChecker = LocalNetworkPermissionChecker { true },
            baseDns = Dns {
                dnsUsed = true
                listOf(InetAddress.getLoopbackAddress())
            },
        )

        val result = transport.execute(call(binding, listOf("v1", "generate")))

        assertEquals(
            ProviderHttpResult.Failure(TransportFailure.UnsafeAcknowledgementRequired),
            result,
        )
        assertFalse(dnsUsed)
    }

    @Test
    fun `cleartext mode cannot be attached to an HTTPS binding`() = runTest {
        var dnsUsed = false
        val endpoint = ProviderEndpoint.parse("https://relay.invalid/base/")
        val profileId = ProfileId("https-cleartext-profile")
        val binding = TransportBinding(
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
        val transport = OkHttpProviderHttpTransport(
            localNetworkPermissionChecker = LocalNetworkPermissionChecker { true },
            baseDns = Dns {
                dnsUsed = true
                listOf(InetAddress.getLoopbackAddress())
            },
        )

        val result = transport.execute(call(binding, listOf("v1", "generate")))

        assertEquals(
            ProviderHttpResult.Failure(TransportFailure.BindingMismatch),
            result,
        )
        assertFalse(dnsUsed)
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
    fun `disconnect after request starts is an uncertain request outcome`() = runTest {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
            val transport = OkHttpProviderHttpTransport(LocalNetworkPermissionChecker { true })

            val result = transport.execute(call(cleartextBinding(server), listOf("v1", "generate")))

            assertEquals(
                ProviderHttpResult.Failure(
                    TransportFailure.RequestOutcomeUnknown(
                        RequestOutcomeUnknownReason.ConnectionLost,
                    ),
                ),
                result,
            )
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
    fun `DNS failure before transmission remains not sent`() = runTest {
        val binding = TransportBinding(
            profileId = ProfileId("dns-failure-profile"),
            endpoint = ProviderEndpoint.parse("https://relay.invalid/base/"),
        )
        val transport = OkHttpProviderHttpTransport(
            localNetworkPermissionChecker = LocalNetworkPermissionChecker { true },
            baseDns = Dns { throw UnknownHostException("offline test") },
        )

        val result = transport.execute(call(binding, listOf("v1", "generate")))

        assertEquals(
            ProviderHttpResult.Failure(
                TransportFailure.Network(
                    NetworkFailureReason.Dns,
                    DeliveryCertainty.NotSent,
                ),
            ),
            result,
        )
    }

    @Test
    fun `non-IO transport exceptions become a typed sanitized failure`() = runTest {
        val binding = TransportBinding(
            profileId = ProfileId("runtime-dns-failure-profile"),
            endpoint = ProviderEndpoint.parse("https://relay.invalid/base/"),
        )
        val transport = OkHttpProviderHttpTransport(
            localNetworkPermissionChecker = LocalNetworkPermissionChecker { true },
            baseDns = Dns { throw IllegalStateException("sentinel-runtime-detail") },
        )

        val result = transport.execute(call(binding, listOf("v1", "generate")))

        assertEquals(
            ProviderHttpResult.Failure(TransportFailure.Unexpected(DeliveryCertainty.NotSent)),
            result,
        )
        assertFalse(result.toString().contains("sentinel-runtime-detail"))
    }

    @Test
    fun `connection failure before transmission remains not sent`() = runTest {
        val server = MockWebServer()
        server.start()
        val binding = cleartextBinding(server)
        server.close()

        val result = OkHttpProviderHttpTransport(LocalNetworkPermissionChecker { true })
            .execute(call(binding, listOf("v1", "generate")))

        assertEquals(
            ProviderHttpResult.Failure(
                TransportFailure.Network(
                    NetworkFailureReason.Connection,
                    DeliveryCertainty.NotSent,
                ),
            ),
            result,
        )
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
    fun `custom CA does not leak into a strict profile`() = runTest {
        tlsServer().use { fixture ->
            fixture.server.enqueue(MockResponse().setBody("custom trusted"))
            val transport = OkHttpProviderHttpTransport(LocalNetworkPermissionChecker { true })
            val endpoint = ProviderEndpoint.parse(fixture.server.url("/base/").toString())
            val customBinding = TransportBinding(
                profileId = ProfileId("custom-profile"),
                endpoint = endpoint,
                securityMode = TransportSecurityMode.CustomCaTls(
                    certificates = listOf(fixture.ca.certificate.encoded),
                ),
            )
            val strictBinding = TransportBinding(
                profileId = ProfileId("strict-profile"),
                endpoint = endpoint,
            )

            val customResult = transport.execute(call(customBinding, listOf("v1", "generate")))
            val strictResult = transport.execute(call(strictBinding, listOf("v1", "generate")))

            assertEquals(
                ProviderHttpResult.Response(200, "custom trusted".encodeToByteArray()),
                customResult,
            )
            assertTrue(
                "result=$strictResult",
                strictResult is ProviderHttpResult.Failure && strictResult.error is TransportFailure.Tls,
            )
            assertEquals(DeliveryCertainty.NotSent, (strictResult as ProviderHttpResult.Failure).error.certainty)
        }
    }

    @Test
    fun `custom CA for one host does not trust a strict second host`() = runTest {
        tlsServer().use { customFixture ->
            tlsServer().use { strictFixture ->
                customFixture.server.enqueue(MockResponse().setBody("custom trusted"))
                val transport = OkHttpProviderHttpTransport(LocalNetworkPermissionChecker { true })
                val customBinding = TransportBinding(
                    profileId = ProfileId("custom-first-host-profile"),
                    endpoint = ProviderEndpoint.parse(customFixture.server.url("/base/").toString()),
                    securityMode = TransportSecurityMode.CustomCaTls(
                        certificates = listOf(customFixture.ca.certificate.encoded),
                    ),
                )
                val strictBinding = TransportBinding(
                    profileId = ProfileId("strict-second-host-profile"),
                    endpoint = ProviderEndpoint.parse(strictFixture.server.url("/base/").toString()),
                )

                val customResult = transport.execute(call(customBinding, listOf("v1", "generate")))
                val strictResult = transport.execute(call(strictBinding, listOf("v1", "generate")))

                assertEquals(
                    ProviderHttpResult.Response(200, "custom trusted".encodeToByteArray()),
                    customResult,
                )
                assertTrue(
                    "result=$strictResult",
                    strictResult is ProviderHttpResult.Failure && strictResult.error is TransportFailure.Tls,
                )
                assertEquals(
                    DeliveryCertainty.NotSent,
                    (strictResult as ProviderHttpResult.Failure).error.certainty,
                )
            }
        }
    }

    @Test
    fun `custom CA client is replaced when the same profile changes authority and certificate`() = runTest {
        tlsServer().use { firstFixture ->
            tlsServer().use { secondFixture ->
                firstFixture.server.enqueue(MockResponse().setBody("first relay"))
                secondFixture.server.enqueue(MockResponse().setBody("second relay"))
                val transport = OkHttpProviderHttpTransport(LocalNetworkPermissionChecker { true })
                val profileId = ProfileId("moving-custom-ca-profile")
                val firstBinding = TransportBinding(
                    profileId = profileId,
                    endpoint = ProviderEndpoint.parse(firstFixture.server.url("/base/").toString()),
                    securityMode = TransportSecurityMode.CustomCaTls(
                        certificates = listOf(firstFixture.ca.certificate.encoded),
                    ),
                )
                val secondBinding = TransportBinding(
                    profileId = profileId,
                    endpoint = ProviderEndpoint.parse(secondFixture.server.url("/base/").toString()),
                    securityMode = TransportSecurityMode.CustomCaTls(
                        certificates = listOf(secondFixture.ca.certificate.encoded),
                    ),
                )

                val first = transport.execute(call(firstBinding, listOf("v1", "generate")))
                val second = transport.execute(call(secondBinding, listOf("v1", "generate")))

                assertEquals(
                    ProviderHttpResult.Response(200, "first relay".encodeToByteArray()),
                    first,
                )
                assertEquals(
                    ProviderHttpResult.Response(200, "second relay".encodeToByteArray()),
                    second,
                )
            }
        }
    }

    @Test
    fun `custom CA retains strict hostname verification`() = runTest {
        tlsServer(serverHostname = "wrong-host.invalid").use { fixture ->
            val endpoint = ProviderEndpoint.parse(fixture.server.url("/base/").toString())
            val binding = TransportBinding(
                profileId = ProfileId("wrong-host-profile"),
                endpoint = endpoint,
                securityMode = TransportSecurityMode.CustomCaTls(
                    certificates = listOf(fixture.ca.certificate.encoded),
                ),
            )

            val result = OkHttpProviderHttpTransport(LocalNetworkPermissionChecker { true })
                .execute(call(binding, listOf("v1", "generate")))

            assertEquals(
                ProviderHttpResult.Failure(
                    TransportFailure.Tls(
                        TlsFailureReason.HostnameMismatch,
                        DeliveryCertainty.NotSent,
                    ),
                ),
                result,
            )
        }
    }

    @Test
    fun `wrong hostname is not misreported as a pin mismatch`() = runTest {
        tlsServer(serverHostname = "wrong-host.invalid").use { fixture ->
            val unrelated = HeldCertificate.Builder().commonName("unrelated").build()
            val endpoint = ProviderEndpoint.parse(fixture.server.url("/base/").toString())
            val binding = TransportBinding(
                profileId = ProfileId("wrong-host-with-pins-profile"),
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
                    TransportFailure.Tls(
                        TlsFailureReason.HostnameMismatch,
                        DeliveryCertainty.NotSent,
                    ),
                ),
                result,
            )
        }
    }

    @Test
    fun `custom CA rejects an expired server certificate`() = runTest {
        tlsServer(validityInterval = 0L..1_000L).use { fixture ->
            val endpoint = ProviderEndpoint.parse(fixture.server.url("/base/").toString())
            val binding = TransportBinding(
                profileId = ProfileId("expired-certificate-profile"),
                endpoint = endpoint,
                securityMode = TransportSecurityMode.CustomCaTls(
                    certificates = listOf(fixture.ca.certificate.encoded),
                ),
            )

            val result = OkHttpProviderHttpTransport(LocalNetworkPermissionChecker { true })
                .execute(call(binding, listOf("v1", "generate")))

            assertEquals(
                ProviderHttpResult.Failure(
                    TransportFailure.Tls(
                        TlsFailureReason.CertificateRejected,
                        DeliveryCertainty.NotSent,
                    ),
                ),
                result,
            )
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
    fun `trust all TLS does not leak into a strict profile`() = runTest {
        tlsServer().use { fixture ->
            fixture.server.enqueue(MockResponse().setBody("unsafe trusted"))
            val transport = OkHttpProviderHttpTransport(LocalNetworkPermissionChecker { true })
            val endpoint = ProviderEndpoint.parse(fixture.server.url("/base/").toString())
            val unsafeProfileId = ProfileId("unsafe-isolation-profile")
            val unsafeBinding = TransportBinding(
                profileId = unsafeProfileId,
                endpoint = endpoint,
                securityMode = TransportSecurityMode.UnsafeTrustAllTls(
                    acknowledgement = UnsafeTransportAcknowledgement(
                        profileId = unsafeProfileId,
                        authority = endpoint.authority,
                        mode = UnsafeTransportMode.TrustAllTls,
                        policyRevision = TransportBinding.CURRENT_POLICY_REVISION,
                        acceptedAtEpochMillis = 1L,
                    ),
                ),
            )
            val strictBinding = TransportBinding(
                profileId = unsafeProfileId,
                endpoint = endpoint,
            )

            val unsafeResult = transport.execute(call(unsafeBinding, listOf("v1", "generate")))
            val strictResult = transport.execute(call(strictBinding, listOf("v1", "generate")))

            assertEquals(
                ProviderHttpResult.Response(200, "unsafe trusted".encodeToByteArray()),
                unsafeResult,
            )
            assertTrue(
                "result=$strictResult",
                strictResult is ProviderHttpResult.Failure && strictResult.error is TransportFailure.Tls,
            )
            assertEquals(DeliveryCertainty.NotSent, (strictResult as ProviderHttpResult.Failure).error.certainty)
        }
    }

    @Test
    fun `pinned server certificate keeps hostname verification by default`() = runTest {
        tlsServer(serverHostname = "certificate-name.invalid").use { fixture ->
            val endpoint = ProviderEndpoint.parse(fixture.server.url("/base/").toString())
            val binding = TransportBinding(
                profileId = ProfileId("strict-pinned-hostname-profile"),
                endpoint = endpoint,
                securityMode = TransportSecurityMode.PinnedServerCertificateTls(
                    certificate = fixture.serverCertificate.certificate.encoded,
                ),
            )

            val result = OkHttpProviderHttpTransport(LocalNetworkPermissionChecker { true })
                .execute(call(binding, listOf("v1", "generate")))

            assertEquals(
                ProviderHttpResult.Failure(
                    TransportFailure.Tls(
                        TlsFailureReason.HostnameMismatch,
                        DeliveryCertainty.NotSent,
                    ),
                ),
                result,
            )
        }
    }

    @Test
    fun `pinned server certificate rejects a different selected certificate`() = runTest {
        tlsServer().use { fixture ->
            val unrelatedCertificate = HeldCertificate.Builder()
                .commonName("localhost")
                .addSubjectAlternativeName("localhost")
                .build()
            val endpoint = ProviderEndpoint.parse(fixture.server.url("/base/").toString())
            val binding = TransportBinding(
                profileId = ProfileId("wrong-selected-certificate-profile"),
                endpoint = endpoint,
                securityMode = TransportSecurityMode.PinnedServerCertificateTls(
                    certificate = unrelatedCertificate.certificate.encoded,
                ),
            )

            val result = OkHttpProviderHttpTransport(LocalNetworkPermissionChecker { true })
                .execute(call(binding, listOf("v1", "generate")))

            assertEquals(
                ProviderHttpResult.Failure(
                    TransportFailure.Tls(
                        TlsFailureReason.PinMismatch,
                        DeliveryCertainty.NotSent,
                    ),
                ),
                result,
            )
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
    fun `timeout after request headers is an uncertain request outcome`() = runTest {
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
                    TransportFailure.RequestOutcomeUnknown(
                        RequestOutcomeUnknownReason.Timeout,
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
                    TransportFailure.RequestOutcomeUnknown(
                        RequestOutcomeUnknownReason.Cancelled,
                    ),
                ),
                pending.await(),
            )
        }
    }

    @Test
    fun `cancellation before transmission remains not sent`() = runTest {
        MockWebServer().use { server ->
            server.start()
            val cancellation = TransportCancellation().apply { cancel() }
            val transport = OkHttpProviderHttpTransport(LocalNetworkPermissionChecker { true })

            val result = transport.execute(
                call(cleartextBinding(server), listOf("v1", "generate")).copy(
                    cancellation = cancellation,
                ),
            )

            assertEquals(
                ProviderHttpResult.Failure(
                    TransportFailure.Cancelled(DeliveryCertainty.NotSent),
                ),
                result,
            )
            assertEquals(0, server.requestCount)
        }
    }

    @Test
    fun `response body failure retains responded certainty`() = runTest {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(
                MockResponse()
                    .setBody("response-body-that-will-be-cut-short")
                    .setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY),
            )
            val transport = OkHttpProviderHttpTransport(LocalNetworkPermissionChecker { true })

            val result = transport.execute(call(cleartextBinding(server), listOf("v1", "generate")))

            assertEquals(
                ProviderHttpResult.Failure(
                    TransportFailure.Network(
                        NetworkFailureReason.Connection,
                        DeliveryCertainty.Responded,
                    ),
                ),
                result,
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
    fun `network supplied NAT64 prefix applies LAN policy to the embedded private address`() = runTest {
        val binding = cleartextBinding("http://relay.invalid")
        val transport = OkHttpProviderHttpTransport(
            localNetworkPermissionChecker = LocalNetworkPermissionChecker { true },
            nat64PrefixProvider = Nat64PrefixProvider {
                Nat64Prefix.parse("2001:db8:64::/96")
            },
            baseDns = Dns { listOf(InetAddress.getByName("2001:db8:64::a00:1")) },
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

    private fun tlsServer(
        serverHostname: String = "localhost",
        validityInterval: LongRange? = null,
    ): TlsServerFixture {
        val ca = HeldCertificate.Builder()
            .certificateAuthority(0)
            .commonName("GNBP test CA")
            .build()
        val certificateBuilder = HeldCertificate.Builder()
            .commonName(serverHostname)
            .addSubjectAlternativeName(serverHostname)
            .signedBy(ca)
        validityInterval?.let { interval ->
            certificateBuilder.validityInterval(interval.first, interval.last)
        }
        val serverCertificate = certificateBuilder.build()
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
