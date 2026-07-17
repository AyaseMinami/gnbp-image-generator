package io.github.ayaseminami.gnbp.provider.transport

import java.net.Inet6Address
import java.net.InetAddress
import org.junit.Assert.assertEquals
import org.junit.Test

class NetworkAddressClassifierTest {
    @Test
    fun `private and special-use addresses are not classified as internet`() {
        val cases = mapOf(
            "10.0.0.1" to NetworkAddressKind.LocalNetwork,
            "172.16.0.1" to NetworkAddressKind.LocalNetwork,
            "192.168.1.1" to NetworkAddressKind.LocalNetwork,
            "169.254.1.1" to NetworkAddressKind.LocalNetwork,
            "fc00::1" to NetworkAddressKind.LocalNetwork,
            "fe80::1" to NetworkAddressKind.LocalNetwork,
            "224.0.0.1" to NetworkAddressKind.LocalNetwork,
            "255.255.255.255" to NetworkAddressKind.LocalNetwork,
            "ff02::1" to NetworkAddressKind.LocalNetwork,
            "127.0.0.1" to NetworkAddressKind.Loopback,
            "::1" to NetworkAddressKind.Loopback,
            "0.0.0.0" to NetworkAddressKind.Unroutable,
            "::" to NetworkAddressKind.Unroutable,
            "8.8.8.8" to NetworkAddressKind.Internet,
            "2001:4860:4860::8888" to NetworkAddressKind.Internet,
        )

        cases.forEach { (literal, expected) ->
            assertEquals(literal, expected, NetworkAddressClassifier.classify(InetAddress.getByName(literal)))
        }
    }

    @Test
    fun `ipv4 mapped ipv6 is classified by its embedded ipv4 address`() {
        val bytes = byteArrayOf(
            0, 0, 0, 0, 0, 0, 0, 0,
            0, 0, 0xff.toByte(), 0xff.toByte(),
            10, 0, 0, 1,
        )
        val mapped = Inet6Address.getByAddress(null, bytes, -1)

        assertEquals(NetworkAddressKind.LocalNetwork, NetworkAddressClassifier.classify(mapped))
    }

    @Test
    fun `nat64 classification follows the embedded ipv4 destination`() {
        val prefix = Nat64Prefix.parse("64:ff9b::/96")

        assertEquals(
            NetworkAddressKind.LocalNetwork,
            NetworkAddressClassifier.classify(InetAddress.getByName("64:ff9b::a00:1"), prefix),
        )
        assertEquals(
            NetworkAddressKind.Internet,
            NetworkAddressClassifier.classify(InetAddress.getByName("64:ff9b::808:808"), prefix),
        )
    }
}
