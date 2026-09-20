package dev.veneranative.source.network

import dev.veneranative.core.model.SourceId
import okhttp3.Cookie
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Per-source cookie isolation is a hard product requirement (`AGENTS.md` 第 6 节), and the in-memory
 * jar has no Android dependency, so it is asserted on the JVM instead of only on a device.
 */
class PerSourceCookieJarRegistryTest {

    private val url: HttpUrl = "https://example.com/chapter/1".toHttpUrl()

    @Test
    fun `the same source always resolves to the same jar`() {
        val registry = PerSourceCookieJarRegistry()
        val sourceId = SourceId("source-a")

        assertSame(registry.forSource(sourceId), registry.forSource(sourceId))
    }

    @Test
    fun `cookies are isolated per source`() {
        val registry = PerSourceCookieJarRegistry()
        val first = registry.forSource(SourceId("source-a"))
        val second = registry.forSource(SourceId("source-b"))

        first.saveFromResponse(url, listOf(cookie(name = "session", value = "a")))

        assertEquals(1, first.loadForRequest(url).size)
        assertTrue(second.loadForRequest(url).isEmpty())
    }

    @Test
    fun `clearing a source drops only that source cookies`() {
        val registry = PerSourceCookieJarRegistry()
        val first = registry.forSource(SourceId("source-a"))
        val second = registry.forSource(SourceId("source-b"))
        first.saveFromResponse(url, listOf(cookie(name = "session", value = "a")))
        second.saveFromResponse(url, listOf(cookie(name = "session", value = "b")))

        registry.clear(SourceId("source-a"))

        assertTrue(registry.forSource(SourceId("source-a")).loadForRequest(url).isEmpty())
        assertEquals(1, registry.forSource(SourceId("source-b")).loadForRequest(url).size)
    }

    @Test
    fun `expired cookies are dropped on write and never returned`() {
        val registry = PerSourceCookieJarRegistry()
        val jar = registry.forSource(SourceId("source-a"))
        val expired = System.currentTimeMillis() - 1_000

        jar.saveFromResponse(url, listOf(cookie(name = "stale", value = "x", expiresAt = expired)))

        assertTrue(jar.loadForRequest(url).isEmpty())
    }

    @Test
    fun `cookies for another host are not returned`() {
        val jar = PerSourceCookieJarRegistry().forSource(SourceId("source-a"))
        jar.saveFromResponse(url, listOf(cookie(name = "session", value = "a")))

        assertTrue(jar.loadForRequest("https://other.example.org/chapter/1".toHttpUrl()).isEmpty())
    }

    private fun cookie(
        name: String,
        value: String,
        expiresAt: Long = Long.MAX_VALUE,
        domain: String = "example.com",
    ): Cookie = Cookie.Builder()
        .name(name)
        .value(value)
        .domain(domain)
        .path("/")
        .expiresAt(expiresAt)
        .build()
}
