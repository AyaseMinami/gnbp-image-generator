package io.github.ayaseminami.gnbp.provider.transport

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class SensitiveValue(
    private val rawValue: String,
) {
    internal fun reveal(): String = rawValue

    override fun toString(): String = "[REDACTED]"
}

enum class HttpMethod {
    Post,
}

data class HttpHeader(
    val name: String,
    val value: SensitiveValue,
    val isSensitive: Boolean,
)

data class HttpQueryParameter(
    val name: String,
    val value: SensitiveValue,
    val isSensitive: Boolean,
)

sealed interface ProviderHttpBody {
    data class Json(val value: String) : ProviderHttpBody {
        override fun toString(): String = "Json([REDACTED])"
    }

    data class Multipart(
        val fields: List<MultipartField>,
        val files: List<MultipartFile>,
    ) : ProviderHttpBody {
        override fun toString(): String = "Multipart(fields=${fields.size}, files=${files.size})"
    }
}

data class MultipartField(
    val name: String,
    val value: String,
) {
    override fun toString(): String = "MultipartField(name=$name, value=[REDACTED])"
}

data class MultipartFile(
    val name: String,
    val fileName: String,
    val mimeType: String,
    val bytes: ByteArray,
) {
    override fun toString(): String =
        "MultipartFile(name=$name, fileName=[REDACTED], mimeType=$mimeType, bytes=${bytes.size})"
}

data class ProviderHttpCall(
    val binding: TransportBinding,
    val method: HttpMethod,
    val pathSegments: List<String>,
    val queryParameters: List<HttpQueryParameter> = emptyList(),
    val headers: List<HttpHeader> = emptyList(),
    val body: ProviderHttpBody,
    val cancellation: TransportCancellation = TransportCancellation(),
) {
    override fun toString(): String =
        "ProviderHttpCall(method=$method, pathSegments=${pathSegments.size}, " +
            "queryParameters=${queryParameters.size}, headers=${headers.map(HttpHeader::name)}, body=$body)"
}

class TransportCancellation {
    private val cancelled = AtomicBoolean(false)
    private val cancelAction = AtomicReference<(() -> Unit)?>(null)

    fun cancel() {
        if (cancelled.compareAndSet(false, true)) {
            cancelAction.get()?.invoke()
        }
    }

    internal fun attach(action: () -> Unit) {
        check(cancelAction.compareAndSet(null, action)) { "Cancellation is already attached" }
        if (cancelled.get()) action()
    }

    internal fun detach() {
        cancelAction.set(null)
    }

    internal fun wasCancelledByCaller(): Boolean = cancelled.get()
}

sealed interface ProviderHttpResult {
    class Response(
        val statusCode: Int,
        body: ByteArray,
    ) : ProviderHttpResult {
        private val bodyBytes = body.copyOf()
        val body: ByteArray get() = bodyBytes.copyOf()

        override fun equals(other: Any?): Boolean =
            other is Response && statusCode == other.statusCode && bodyBytes.contentEquals(other.bodyBytes)

        override fun hashCode(): Int = 31 * statusCode + bodyBytes.contentHashCode()

        override fun toString(): String = "Response(statusCode=$statusCode, bodyBytes=${bodyBytes.size})"
    }

    data class Failure(val error: TransportFailure) : ProviderHttpResult
}

enum class DeliveryCertainty {
    NotSent,
    PossiblySent,
    Responded,
}

sealed interface TransportFailure {
    val certainty: DeliveryCertainty

    data class InvalidRequest(
        val reason: String,
        override val certainty: DeliveryCertainty = DeliveryCertainty.NotSent,
    ) : TransportFailure

    data class Network(
        val reason: NetworkFailureReason,
        override val certainty: DeliveryCertainty,
    ) : TransportFailure

    data class Tls(
        val reason: TlsFailureReason,
        override val certainty: DeliveryCertainty = DeliveryCertainty.NotSent,
    ) : TransportFailure

    data class RedirectRejected(
        val statusCode: Int,
        override val certainty: DeliveryCertainty = DeliveryCertainty.Responded,
    ) : TransportFailure

    data object CleartextRejected : TransportFailure {
        override val certainty: DeliveryCertainty = DeliveryCertainty.NotSent
    }

    data object UnsafeAcknowledgementStale : TransportFailure {
        override val certainty: DeliveryCertainty = DeliveryCertainty.NotSent
    }

    data object UnsafeAcknowledgementRequired : TransportFailure {
        override val certainty: DeliveryCertainty = DeliveryCertainty.NotSent
    }

    data object BindingMismatch : TransportFailure {
        override val certainty: DeliveryCertainty = DeliveryCertainty.NotSent
    }

    data class Unexpected(
        override val certainty: DeliveryCertainty,
    ) : TransportFailure

    data class Cancelled(
        override val certainty: DeliveryCertainty,
    ) : TransportFailure

    data class RequestOutcomeUnknown(
        val reason: RequestOutcomeUnknownReason,
    ) : TransportFailure {
        override val certainty: DeliveryCertainty = DeliveryCertainty.PossiblySent
    }
}

enum class RequestOutcomeUnknownReason {
    Cancelled,
    Timeout,
    ConnectionLost,
}

enum class NetworkFailureReason {
    Dns,
    Connection,
    Timeout,
    LocalNetworkDisabled,
    LocalNetworkPermissionRequired,
    UnroutableAddress,
    ResponseTooLarge,
}

enum class TlsFailureReason {
    CertificateRejected,
    HostnameMismatch,
    PinMismatch,
    Handshake,
}

interface ProviderHttpTransport {
    suspend fun execute(call: ProviderHttpCall): ProviderHttpResult
}
