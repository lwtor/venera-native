package dev.veneranative.source.network

import java.net.InetAddress
import okhttp3.Dns
import org.junit.Assert.assertEquals
import org.junit.Test

class IkmhDnsFallbackTest {
    @Test
    fun `ikmmh host uses secure DNS addresses before system DNS`() {
        val system = address("192.0.2.1")
        val secure = address("192.0.2.2")
        val dns = IkmhDnsFallback(
            systemDns = Dns { listOf(system) },
            fallbackDns = Dns { listOf(secure) },
        )

        assertEquals(listOf(secure, system), dns.lookup("www.ikmmh.com"))
        assertEquals(listOf(secure, system), dns.lookup("IKMMH.COM"))
    }

    @Test
    fun `other domains use system DNS only`() {
        val system = address("192.0.2.1")
        val dns = IkmhDnsFallback(
            systemDns = Dns { listOf(system) },
            fallbackDns = Dns { error("fallback must not be used") },
        )

        assertEquals(listOf(system), dns.lookup("example.com"))
    }

    private fun address(value: String): InetAddress = InetAddress.getByName(value)
}
