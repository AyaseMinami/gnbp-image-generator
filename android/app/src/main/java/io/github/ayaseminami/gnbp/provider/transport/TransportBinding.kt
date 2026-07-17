package io.github.ayaseminami.gnbp.provider.transport

import java.net.URI
import okhttp3.HttpUrl.Companion.toHttpUrl

@JvmInline
value class ProfileId(val value: String) {
    init {
        require(value.isNotBlank()) { "Profile ID must not be blank" }
    }
}

data class EndpointAuthority(
    val scheme: String,
    val asciiHost: String,
    val effectivePort: Int,
)

class ProviderEndpoint private constructor(
    val authority: EndpointAuthority,
    val basePath: String,
) {
    companion object {
        fun parse(value: String): ProviderEndpoint {
            val uri = runCatching { URI(value) }
                .getOrElse { throw IllegalArgumentException("Invalid provider endpoint", it) }
            require(uri.rawUserInfo == null) { "Provider endpoint must not contain user information" }
            require(uri.rawQuery == null) { "Provider endpoint must not contain a query" }
            require(uri.rawFragment == null) { "Provider endpoint must not contain a fragment" }
            val url = runCatching { value.toHttpUrl() }
                .getOrElse { throw IllegalArgumentException("Invalid HTTP provider endpoint", it) }

            return ProviderEndpoint(
                authority = EndpointAuthority(url.scheme, url.host, url.port),
                basePath = url.encodedPath,
            )
        }
    }
}

enum class UnsafeTransportMode {
    TrustAllTls,
    CleartextHttp,
}

data class UnsafeTransportAcknowledgement(
    val profileId: ProfileId,
    val authority: EndpointAuthority,
    val mode: UnsafeTransportMode,
    val policyRevision: Int,
    val acceptedAtEpochMillis: Long,
) {
    init {
        require(policyRevision > 0) { "Acknowledgement policy revision must be positive" }
        require(acceptedAtEpochMillis > 0) { "Acknowledgement time must be positive" }
    }
}

sealed interface TransportSecurityMode {
    data object VerifiedTls : TransportSecurityMode

    class CustomCaTls(
        certificates: List<ByteArray>,
        spkiPins: Set<String> = emptySet(),
    ) : TransportSecurityMode {
        val certificates: List<ByteArray> = certificates.map(ByteArray::copyOf)
        val spkiPins: Set<String> = spkiPins.toSet()

        init {
            require(certificates.isNotEmpty()) { "Custom CA mode requires a certificate" }
            require(certificates.none(ByteArray::isEmpty)) { "Custom CA certificate must not be empty" }
        }
    }

    class PinnedServerCertificateTls(
        certificate: ByteArray,
        val allowHostnameMismatch: Boolean = false,
    ) : TransportSecurityMode {
        val certificate: ByteArray = certificate.copyOf()

        init {
            require(certificate.isNotEmpty()) { "Pinned server certificate must not be empty" }
        }
    }

    data class UnsafeTrustAllTls(
        val acknowledgement: UnsafeTransportAcknowledgement? = null,
    ) : TransportSecurityMode

    data class CleartextHttp(
        val acknowledgement: UnsafeTransportAcknowledgement? = null,
    ) : TransportSecurityMode
}

enum class LocalNetworkMode {
    InternetOrLoopbackOnly,
    AllowLan,
}

data class TransportBinding(
    val profileId: ProfileId,
    val endpoint: ProviderEndpoint,
    val securityMode: TransportSecurityMode = TransportSecurityMode.VerifiedTls,
    val localNetworkMode: LocalNetworkMode = LocalNetworkMode.InternetOrLoopbackOnly,
    val policyRevision: Int = CURRENT_POLICY_REVISION,
) {
    init {
        require(policyRevision > 0) { "Transport policy revision must be positive" }
    }

    override fun toString(): String =
        "TransportBinding(profileId=[REDACTED], endpoint=[REDACTED], " +
            "securityMode=${securityMode::class.simpleName}, localNetworkMode=$localNetworkMode, " +
            "policyRevision=$policyRevision)"

    companion object {
        const val CURRENT_POLICY_REVISION = 1
    }
}

internal fun TransportBinding.validationFailure(): TransportFailure? {
    val isUnsafe = securityMode is TransportSecurityMode.UnsafeTrustAllTls ||
        securityMode is TransportSecurityMode.CleartextHttp
    if (policyRevision != TransportBinding.CURRENT_POLICY_REVISION) {
        return if (isUnsafe) {
            TransportFailure.UnsafeAcknowledgementStale
        } else {
            TransportFailure.BindingMismatch
        }
    }

    return when (val mode = securityMode) {
        TransportSecurityMode.VerifiedTls,
        is TransportSecurityMode.CustomCaTls,
        is TransportSecurityMode.PinnedServerCertificateTls,
        -> if (endpoint.authority.scheme == "https") null else TransportFailure.CleartextRejected
        is TransportSecurityMode.UnsafeTrustAllTls -> when {
            endpoint.authority.scheme != "https" -> TransportFailure.BindingMismatch
            else -> acknowledgementFailure(mode.acknowledgement, UnsafeTransportMode.TrustAllTls)
        }
        is TransportSecurityMode.CleartextHttp -> when {
            endpoint.authority.scheme != "http" -> TransportFailure.BindingMismatch
            else -> acknowledgementFailure(mode.acknowledgement, UnsafeTransportMode.CleartextHttp)
        }
    }
}

private fun TransportBinding.acknowledgementFailure(
    acknowledgement: UnsafeTransportAcknowledgement?,
    expectedMode: UnsafeTransportMode,
): TransportFailure? = when {
    acknowledgement == null -> TransportFailure.UnsafeAcknowledgementRequired
    acknowledgement.policyRevision != TransportBinding.CURRENT_POLICY_REVISION ->
        TransportFailure.UnsafeAcknowledgementStale
    acknowledgement.profileId != profileId ||
        acknowledgement.authority != endpoint.authority ||
        acknowledgement.mode != expectedMode ||
        acknowledgement.policyRevision != policyRevision -> TransportFailure.BindingMismatch
    else -> null
}
