package io.github.ayaseminami.gnbp.provider.transport

import android.annotation.SuppressLint
import java.io.IOException
import java.io.InterruptedIOException
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.io.ByteArrayInputStream
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.SSLException
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Dns
import okhttp3.EventListener
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.CertificatePinner
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

internal fun interface LocalNetworkPermissionChecker {
    fun isGrantedWhenRequired(): Boolean
}

internal class OkHttpProviderHttpTransport(
    private val localNetworkPermissionChecker: LocalNetworkPermissionChecker,
    private val nat64Prefix: Nat64Prefix? = null,
    private val maxResponseBytes: Long = DEFAULT_MAX_RESPONSE_BYTES,
    private val callTimeoutMillis: Long = DEFAULT_CALL_TIMEOUT_MILLIS,
    private val baseDns: Dns = Dns.SYSTEM,
) : ProviderHttpTransport {
    private val clientsByProfile = ConcurrentHashMap<ProfileId, CachedClient>()

    override suspend fun execute(call: ProviderHttpCall): ProviderHttpResult = withContext(Dispatchers.IO) {
        call.binding.validationFailure()?.let { failure ->
            return@withContext ProviderHttpResult.Failure(failure)
        }
        val tracker = DeliveryTracker()
        val request = try {
            call.toOkHttpRequest(tracker)
        } catch (_: IllegalArgumentException) {
            return@withContext ProviderHttpResult.Failure(
                TransportFailure.InvalidRequest("Invalid transport request"),
            )
        }
        val client = try {
            clientFor(call.binding)
        } catch (_: Exception) {
            return@withContext ProviderHttpResult.Failure(
                TransportFailure.InvalidRequest("Invalid transport security configuration"),
            )
        }
        val networkCall = client.newCall(request)
        call.cancellation.attach(networkCall::cancel)
        try {
            networkCall.execute().use { response ->
                if (response.code in 300..399) {
                    return@withContext ProviderHttpResult.Failure(
                        TransportFailure.RedirectRejected(response.code),
                    )
                }
                val body = response.body
                val source = body.source()
                source.request(maxResponseBytes + 1)
                if (source.buffer.size > maxResponseBytes) {
                    return@withContext ProviderHttpResult.Failure(
                        TransportFailure.Network(
                            NetworkFailureReason.ResponseTooLarge,
                            DeliveryCertainty.Responded,
                        ),
                    )
                }
                ProviderHttpResult.Response(response.code, source.readByteArray())
            }
        } catch (error: PolicyDnsException) {
            ProviderHttpResult.Failure(
                TransportFailure.Network(error.reason, tracker.certainty()),
            )
        } catch (error: SSLPeerUnverifiedException) {
            ProviderHttpResult.Failure(
                TransportFailure.Tls(error.peerVerificationFailure(call.binding), tracker.certainty()),
            )
        } catch (error: SSLHandshakeException) {
            ProviderHttpResult.Failure(
                TransportFailure.Tls(
                    if (error.findNested<PinnedCertificateMismatchException>() != null) {
                        TlsFailureReason.PinMismatch
                    } else {
                        TlsFailureReason.CertificateRejected
                    },
                    tracker.certainty(),
                ),
            )
        } catch (error: SSLException) {
            ProviderHttpResult.Failure(
                TransportFailure.Tls(TlsFailureReason.Handshake, tracker.certainty()),
            )
        } catch (error: SocketTimeoutException) {
            ProviderHttpResult.Failure(
                deliveryAwareFailure(
                    certainty = tracker.certainty(),
                    unknownReason = RequestOutcomeUnknownReason.Timeout,
                ) { certainty ->
                    TransportFailure.Network(NetworkFailureReason.Timeout, certainty)
                },
            )
        } catch (error: UnknownHostException) {
            val policyError = error.findPolicyDnsException()
            ProviderHttpResult.Failure(
                TransportFailure.Network(
                    policyError?.reason ?: NetworkFailureReason.Dns,
                    tracker.certainty(),
                ),
            )
        } catch (error: IOException) {
            val peerVerificationError = error.findNested<SSLPeerUnverifiedException>()
            val failure = when {
                call.cancellation.wasCancelledByCaller() -> deliveryAwareFailure(
                    certainty = tracker.certainty(),
                    unknownReason = RequestOutcomeUnknownReason.Cancelled,
                    knownFailure = TransportFailure::Cancelled,
                )
                error.findNested<InterruptedIOException>() != null -> deliveryAwareFailure(
                    certainty = tracker.certainty(),
                    unknownReason = RequestOutcomeUnknownReason.Timeout,
                ) { certainty ->
                    TransportFailure.Network(NetworkFailureReason.Timeout, certainty)
                }
                peerVerificationError != null -> TransportFailure.Tls(
                    peerVerificationError.peerVerificationFailure(call.binding),
                    tracker.certainty(),
                )
                error.findNested<PinnedCertificateMismatchException>() != null -> TransportFailure.Tls(
                    TlsFailureReason.PinMismatch,
                    tracker.certainty(),
                )
                error.findNested<CertificateException>() != null -> TransportFailure.Tls(
                    TlsFailureReason.CertificateRejected,
                    tracker.certainty(),
                )
                error.findNested<SSLException>() != null -> TransportFailure.Tls(
                    TlsFailureReason.Handshake,
                    tracker.certainty(),
                )
                else -> deliveryAwareFailure(
                    certainty = tracker.certainty(),
                    unknownReason = RequestOutcomeUnknownReason.ConnectionLost,
                ) { certainty ->
                    TransportFailure.Network(NetworkFailureReason.Connection, certainty)
                }
            }
            ProviderHttpResult.Failure(failure)
        } finally {
            call.cancellation.detach()
        }
    }

    private fun clientFor(binding: TransportBinding): OkHttpClient {
        val fingerprint = binding.clientFingerprint()
        return clientsByProfile.compute(binding.profileId) { _, existing ->
            if (existing?.fingerprint == fingerprint) {
                existing
            } else {
                val replacement = CachedClient(fingerprint, buildClient(binding))
                existing?.client?.connectionPool?.evictAll()
                replacement
            }
        }!!.client
    }

    private fun buildClient(binding: TransportBinding): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .retryOnConnectionFailure(false)
            .followRedirects(false)
            .followSslRedirects(false)
            .connectTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(400, TimeUnit.SECONDS)
            .readTimeout(400, TimeUnit.SECONDS)
            .callTimeout(callTimeoutMillis, TimeUnit.MILLISECONDS)
            .dns(BindingDns(binding, localNetworkPermissionChecker, nat64Prefix, baseDns))
            .eventListenerFactory { call ->
                DeliveryEventListener(requireNotNull(call.request().tag(DeliveryTracker::class.java)))
            }
        when (val mode = binding.securityMode) {
            TransportSecurityMode.VerifiedTls,
            is TransportSecurityMode.CleartextHttp,
            -> Unit
            is TransportSecurityMode.CustomCaTls -> {
                val trustManager = exclusiveTrustManager(mode.certificates)
                builder.sslSocketFactory(sslContext(trustManager).socketFactory, trustManager)
                if (mode.spkiPins.isNotEmpty()) {
                    builder.certificatePinner(
                        CertificatePinner.Builder()
                            .add(binding.endpoint.authority.asciiHost, *mode.spkiPins.toTypedArray())
                            .build(),
                    )
                }
            }
            is TransportSecurityMode.PinnedServerCertificateTls -> {
                val certificate = parseCertificates(listOf(mode.certificate)).single()
                val trustManager = PinnedCertificateTrustManager(certificate)
                builder.sslSocketFactory(sslContext(trustManager).socketFactory, trustManager)
                if (mode.allowHostnameMismatch) {
                    val expectedHost = binding.endpoint.authority.asciiHost
                    builder.hostnameVerifier { hostname, _ -> hostname.equals(expectedHost, ignoreCase = true) }
                }
            }
            is TransportSecurityMode.UnsafeTrustAllTls -> {
                val trustManager = TrustAllManager
                val expectedHost = binding.endpoint.authority.asciiHost
                builder.sslSocketFactory(sslContext(trustManager).socketFactory, trustManager)
                builder.hostnameVerifier { hostname, _ -> hostname.equals(expectedHost, ignoreCase = true) }
            }
        }
        return builder.build()
    }

    private fun ProviderHttpCall.toOkHttpRequest(tracker: DeliveryTracker): Request {
        val authority = binding.endpoint.authority
        val url = okhttp3.HttpUrl.Builder()
            .scheme(authority.scheme)
            .host(authority.asciiHost)
            .port(authority.effectivePort)
            .encodedPath(binding.endpoint.basePath)
            .apply {
                pathSegments.forEach(::addPathSegment)
                queryParameters.forEach { addQueryParameter(it.name, it.value.reveal()) }
            }
            .build()
        require(url.scheme == authority.scheme && url.host == authority.asciiHost && url.port == authority.effectivePort) {
            "Request authority does not match transport binding"
        }
        val requestBody = when (val payload = body) {
            is ProviderHttpBody.Json -> payload.value.toRequestBody(JSON_MEDIA_TYPE)
            is ProviderHttpBody.Multipart -> MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .apply {
                    payload.fields.forEach { addFormDataPart(it.name, it.value) }
                    payload.files.forEach { file ->
                        addFormDataPart(
                            file.name,
                            file.fileName,
                            file.bytes.toRequestBody(file.mimeType.toMediaType()),
                        )
                    }
                }
                .build()
        }
        return Request.Builder()
            .url(url)
            .tag(DeliveryTracker::class.java, tracker)
            .apply {
                this@toOkHttpRequest.headers.forEach { transportHeader ->
                    header(transportHeader.name, transportHeader.value.reveal())
                }
            }
            .post(requestBody)
            .build()
    }

    private companion object {
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        const val DEFAULT_MAX_RESPONSE_BYTES = 64L * 1024L * 1024L
        const val DEFAULT_CALL_TIMEOUT_MILLIS = 410_000L
    }
}

private data class CachedClient(
    val fingerprint: ClientFingerprint,
    val client: OkHttpClient,
)

private data class ClientFingerprint(
    val profileId: ProfileId,
    val authority: EndpointAuthority,
    val security: SecurityFingerprint,
    val localNetworkMode: LocalNetworkMode,
    val policyRevision: Int,
)

private sealed interface SecurityFingerprint {
    data object VerifiedTls : SecurityFingerprint

    data class CustomCaTls(
        val certificateDigests: List<String>,
        val spkiPins: List<String>,
    ) : SecurityFingerprint

    data class PinnedServerCertificateTls(
        val certificateDigest: String,
        val allowHostnameMismatch: Boolean,
    ) : SecurityFingerprint

    data object UnsafeTrustAllTls : SecurityFingerprint

    data object CleartextHttp : SecurityFingerprint
}

private fun TransportBinding.clientFingerprint(): ClientFingerprint = ClientFingerprint(
    profileId = profileId,
    authority = endpoint.authority,
    security = when (val mode = securityMode) {
        TransportSecurityMode.VerifiedTls -> SecurityFingerprint.VerifiedTls
        is TransportSecurityMode.CustomCaTls -> SecurityFingerprint.CustomCaTls(
            certificateDigests = mode.certificates.map(ByteArray::sha256Hex).sorted(),
            spkiPins = mode.spkiPins.sorted(),
        )
        is TransportSecurityMode.PinnedServerCertificateTls ->
            SecurityFingerprint.PinnedServerCertificateTls(
                certificateDigest = mode.certificate.sha256Hex(),
                allowHostnameMismatch = mode.allowHostnameMismatch,
            )
        is TransportSecurityMode.UnsafeTrustAllTls -> SecurityFingerprint.UnsafeTrustAllTls
        is TransportSecurityMode.CleartextHttp -> SecurityFingerprint.CleartextHttp
    },
    localNetworkMode = localNetworkMode,
    policyRevision = policyRevision,
)

private fun ByteArray.sha256Hex(): String = MessageDigest.getInstance("SHA-256")
    .digest(this)
    .joinToString(separator = "") { byte -> "%02x".format(byte) }

private fun exclusiveTrustManager(certificateBytes: List<ByteArray>): X509TrustManager {
    val certificates = parseCertificates(certificateBytes)
    require(certificates.all { it.basicConstraints >= 0 }) {
        "Custom CA mode accepts CA certificates only"
    }
    val keyStore = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null) }
    certificates.forEachIndexed { index, certificate ->
        keyStore.setCertificateEntry("gnbp-custom-ca-$index", certificate)
    }
    val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply {
        init(keyStore)
    }
    return factory.trustManagers.filterIsInstance<X509TrustManager>().single()
}

private fun parseCertificates(blobs: List<ByteArray>): List<X509Certificate> {
    val factory = CertificateFactory.getInstance("X.509")
    return blobs.flatMap { blob ->
        factory.generateCertificates(ByteArrayInputStream(blob)).map { certificate ->
            certificate as? X509Certificate
                ?: throw CertificateException("Certificate is not X.509")
        }
    }.also { require(it.isNotEmpty()) { "No X.509 certificate found" } }
}

private fun sslContext(trustManager: X509TrustManager): SSLContext =
    SSLContext.getInstance("TLS").apply {
        init(null, arrayOf(trustManager), SecureRandom())
    }

@SuppressLint("CustomX509TrustManager")
private class PinnedCertificateTrustManager(
    private val pinnedCertificate: X509Certificate,
) : X509TrustManager {
    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        throw CertificateException("Client certificates are not supported")
    }

    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        val leaf = chain?.firstOrNull() ?: throw CertificateException("Server sent no certificate")
        leaf.checkValidity()
        if (!MessageDigest.isEqual(leaf.encoded, pinnedCertificate.encoded)) {
            throw PinnedCertificateMismatchException()
        }
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf(pinnedCertificate)
}

private class PinnedCertificateMismatchException :
    CertificateException("Server certificate does not match the selected pin")

@SuppressLint("CustomX509TrustManager")
private object TrustAllManager : X509TrustManager {
    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit

    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}

private class DeliveryTracker {
    private val certainty = AtomicReference(DeliveryCertainty.NotSent)

    fun markPossiblySent() {
        certainty.compareAndSet(DeliveryCertainty.NotSent, DeliveryCertainty.PossiblySent)
    }

    fun markResponded() {
        certainty.set(DeliveryCertainty.Responded)
    }

    fun certainty(): DeliveryCertainty = certainty.get()
}

private class DeliveryEventListener(
    private val tracker: DeliveryTracker,
) : EventListener() {
    override fun requestHeadersStart(call: Call) {
        tracker.markPossiblySent()
    }

    override fun responseHeadersStart(call: Call) {
        tracker.markResponded()
    }
}

private fun deliveryAwareFailure(
    certainty: DeliveryCertainty,
    unknownReason: RequestOutcomeUnknownReason,
    knownFailure: (DeliveryCertainty) -> TransportFailure,
): TransportFailure = when (certainty) {
    DeliveryCertainty.PossiblySent -> TransportFailure.RequestOutcomeUnknown(unknownReason)
    DeliveryCertainty.NotSent,
    DeliveryCertainty.Responded,
    -> knownFailure(certainty)
}

private class BindingDns(
    private val binding: TransportBinding,
    private val permissionChecker: LocalNetworkPermissionChecker,
    private val nat64Prefix: Nat64Prefix?,
    private val baseDns: Dns,
) : Dns {
    override fun lookup(hostname: String): List<InetAddress> {
        val addresses = try {
            baseDns.lookup(hostname)
        } catch (error: UnknownHostException) {
            throw error
        }
        addresses.forEach { address ->
            when (NetworkAddressClassifier.classify(address, nat64Prefix)) {
                NetworkAddressKind.Internet,
                NetworkAddressKind.Loopback,
                -> Unit
                NetworkAddressKind.Unroutable -> throw PolicyDnsException(
                    NetworkFailureReason.UnroutableAddress,
                )
                NetworkAddressKind.LocalNetwork -> when {
                    binding.localNetworkMode != LocalNetworkMode.AllowLan -> throw PolicyDnsException(
                        NetworkFailureReason.LocalNetworkDisabled,
                    )
                    !permissionChecker.isGrantedWhenRequired() -> throw PolicyDnsException(
                        NetworkFailureReason.LocalNetworkPermissionRequired,
                    )
                }
            }
        }
        return addresses
    }
}

private fun SSLPeerUnverifiedException.peerVerificationFailure(
    binding: TransportBinding,
): TlsFailureReason =
    if (
        (binding.securityMode as? TransportSecurityMode.CustomCaTls)?.spkiPins?.isNotEmpty() == true &&
        message?.startsWith("Certificate pinning failure!") == true
    ) {
        TlsFailureReason.PinMismatch
    } else {
        TlsFailureReason.HostnameMismatch
    }

private class PolicyDnsException(
    val reason: NetworkFailureReason,
) : UnknownHostException("Destination rejected by local-network policy")

private fun Throwable.findPolicyDnsException(): PolicyDnsException? {
    var current: Throwable? = this
    while (current != null) {
        if (current is PolicyDnsException) return current
        current = current.cause
    }
    return null
}

private inline fun <reified T : Throwable> Throwable.findNested(): T? {
    val pending = ArrayDeque<Throwable>()
    val visited = java.util.Collections.newSetFromMap(
        java.util.IdentityHashMap<Throwable, Boolean>(),
    )
    pending.add(this)
    while (pending.isNotEmpty()) {
        val current = pending.removeFirst()
        if (!visited.add(current)) continue
        if (current is T) return current
        current.cause?.let(pending::addLast)
        current.suppressed.forEach(pending::addLast)
    }
    return null
}
