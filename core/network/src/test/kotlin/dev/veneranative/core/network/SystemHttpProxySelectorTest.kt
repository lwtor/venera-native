package dev.veneranative.core.network

import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class SystemHttpProxySelectorTest {
    @Test fun usesCurrentProxyAndPreservesHostForProxyResolution() {
        val selector = SystemHttpProxySelector(
            currentProxy = { SystemHttpProxy("127.0.0.1", 7890) },
            fallback = null,
        )

        val proxy = selector.select(URI("https://komiic.com/api/query")).single()

        assertEquals(Proxy.Type.HTTP, proxy.type())
        assertEquals("127.0.0.1", (proxy.address() as java.net.InetSocketAddress).hostString)
        assertEquals(7890, (proxy.address() as java.net.InetSocketAddress).port)
    }

    @Test fun bypassesExactAndWildcardHosts() {
        val selector = SystemHttpProxySelector(
            currentProxy = { SystemHttpProxy("127.0.0.1", 7890, listOf("localhost", "*.local", "*zhihu.com")) },
            fallback = null,
        )

        assertSame(Proxy.NO_PROXY, selector.select(URI("https://localhost/")).single())
        assertSame(Proxy.NO_PROXY, selector.select(URI("https://printer.local/")).single())
        assertSame(Proxy.NO_PROXY, selector.select(URI("https://www.zhihu.com/")).single())
        assertEquals(Proxy.Type.HTTP, selector.select(URI("https://komiic.com/")).single().type())
    }

    @Test fun fallsBackWhenNoAndroidProxyIsPublished() {
        val systemProxy = Proxy(Proxy.Type.HTTP, java.net.InetSocketAddress("fallback.local", 8080))
        val fallback = object : ProxySelector() {
            override fun select(uri: URI) = listOf(systemProxy)
            override fun connectFailed(uri: URI, sa: SocketAddress, ioe: java.io.IOException) = Unit
        }
        val selector = SystemHttpProxySelector(currentProxy = { null }, fallback = fallback)

        assertSame(systemProxy, selector.select(URI("https://komiic.com/")).single())
    }
}
