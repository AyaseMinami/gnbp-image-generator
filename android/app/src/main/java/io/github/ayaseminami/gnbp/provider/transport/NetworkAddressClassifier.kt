package io.github.ayaseminami.gnbp.provider.transport

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress

internal enum class NetworkAddressKind {
    Internet,
    LocalNetwork,
    Loopback,
    Unroutable,
}

internal class Nat64Prefix private constructor(
    private val networkBytes: ByteArray,
) {
    fun extractIpv4(address: Inet6Address): ByteArray? {
        val bytes = address.address
        return if (bytes.copyOfRange(0, PREFIX_BYTES).contentEquals(networkBytes)) {
            bytes.copyOfRange(PREFIX_BYTES, IPV6_BYTES)
        } else {
            null
        }
    }

    companion object {
        fun parse(value: String): Nat64Prefix {
            val separator = value.lastIndexOf('/')
            require(separator > 0 && value.substring(separator + 1) == "96") {
                "Only /96 NAT64 prefixes are supported"
            }
            val address = InetAddress.getByName(value.substring(0, separator))
            require(address is Inet6Address) { "NAT64 prefix must be IPv6" }
            val bytes = address.address
            require(bytes.copyOfRange(PREFIX_BYTES, IPV6_BYTES).all { it == 0.toByte() }) {
                "NAT64 prefix has host bits set"
            }
            return Nat64Prefix(bytes.copyOfRange(0, PREFIX_BYTES))
        }

        private const val PREFIX_BYTES = 12
        private const val IPV6_BYTES = 16
    }
}

internal object NetworkAddressClassifier {
    fun classify(
        address: InetAddress,
        nat64Prefix: Nat64Prefix? = null,
    ): NetworkAddressKind = when (address) {
        is Inet4Address -> classifyIpv4(address.address)
        is Inet6Address -> classifyIpv6(address, nat64Prefix)
        else -> NetworkAddressKind.Unroutable
    }

    private fun classifyIpv6(
        address: Inet6Address,
        nat64Prefix: Nat64Prefix?,
    ): NetworkAddressKind {
        val bytes = address.address
        if (bytes.all { it == 0.toByte() }) return NetworkAddressKind.Unroutable
        if (address.isLoopbackAddress) return NetworkAddressKind.Loopback
        mappedIpv4(bytes)?.let { return classifyIpv4(it) }
        nat64Prefix?.extractIpv4(address)?.let { return classifyIpv4(it) }

        val first = bytes[0].unsigned()
        val second = bytes[1].unsigned()
        return when {
            first and 0xfe == 0xfc -> NetworkAddressKind.LocalNetwork
            first == 0xfe && second and 0xc0 == 0x80 -> NetworkAddressKind.LocalNetwork
            first == 0xfe && second and 0xc0 == 0xc0 -> NetworkAddressKind.LocalNetwork
            first == 0xff -> NetworkAddressKind.LocalNetwork
            else -> NetworkAddressKind.Internet
        }
    }

    private fun classifyIpv4(bytes: ByteArray): NetworkAddressKind {
        val first = bytes[0].unsigned()
        val second = bytes[1].unsigned()
        return when {
            first == 0 -> NetworkAddressKind.Unroutable
            first == 127 -> NetworkAddressKind.Loopback
            first == 10 -> NetworkAddressKind.LocalNetwork
            first == 172 && second in 16..31 -> NetworkAddressKind.LocalNetwork
            first == 192 && second == 168 -> NetworkAddressKind.LocalNetwork
            first == 169 && second == 254 -> NetworkAddressKind.LocalNetwork
            first in 224..239 -> NetworkAddressKind.LocalNetwork
            bytes.all { it.unsigned() == 255 } -> NetworkAddressKind.LocalNetwork
            first == 255 -> NetworkAddressKind.Unroutable
            else -> NetworkAddressKind.Internet
        }
    }

    private fun mappedIpv4(bytes: ByteArray): ByteArray? {
        val hasMappedPrefix = bytes.copyOfRange(0, 10).all { it == 0.toByte() } &&
            bytes[10].unsigned() == 0xff &&
            bytes[11].unsigned() == 0xff
        return if (hasMappedPrefix) bytes.copyOfRange(12, 16) else null
    }
}

private fun Byte.unsigned(): Int = toInt() and 0xff
