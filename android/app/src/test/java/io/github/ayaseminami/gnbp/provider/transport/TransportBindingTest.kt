package io.github.ayaseminami.gnbp.provider.transport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class TransportBindingTest {
    @Test
    fun `sensitive transport values never reveal their content through toString`() {
        assertEquals("[REDACTED]", SensitiveValue("sentinel-secret").toString())
    }

    @Test
    fun `endpoint parsing canonicalizes authority and keeps the base path`() {
        val endpoint = ProviderEndpoint.parse("HTTPS://Relay.Example:443/api/")

        assertEquals(EndpointAuthority("https", "relay.example", 443), endpoint.authority)
        assertEquals("/api/", endpoint.basePath)
    }

    @Test
    fun `endpoint parsing canonicalizes an IDN host to ASCII`() {
        val endpoint = ProviderEndpoint.parse("https://b\u00fccher.example/api")

        assertEquals("xn--bcher-kva.example", endpoint.authority.asciiHost)
    }

    @Test
    fun `endpoint parsing rejects user information query and fragment`() {
        assertThrows(IllegalArgumentException::class.java) {
            ProviderEndpoint.parse("https://user:pass@relay.example/v1")
        }
        assertThrows(IllegalArgumentException::class.java) {
            ProviderEndpoint.parse("https://relay.example/v1?key=secret")
        }
        assertThrows(IllegalArgumentException::class.java) {
            ProviderEndpoint.parse("https://relay.example/v1#fragment")
        }
    }
}
