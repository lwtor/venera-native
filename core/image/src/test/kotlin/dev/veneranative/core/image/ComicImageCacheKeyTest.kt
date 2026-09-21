package dev.veneranative.core.image

import dev.veneranative.core.model.SourceId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * The rules that decide whether two requests may share cached bytes.
 *
 * These are the whole point of having our own key: a comic image is fetched with that source's
 * cookies and headers, so a key that ignored them would serve one identity's image to another, and a
 * key that included volatile headers would fragment the cache until it never hit.
 */
class ComicImageCacheKeyTest {

    private val sourceId = SourceId("source-a")

    private val url = "https://images.example/comic/1/page-3.webp"

    @Test
    fun `the same request always produces the same key`() {
        val request = ComicImageRequest(url = url, sourceId = sourceId)

        assertEquals(key(request), key(request))
    }

    @Test
    fun `a different cookie is a different key`() {
        val first = ComicImageRequest(url = url, sourceId = sourceId)
        val second = ComicImageRequest(url = url, sourceId = sourceId)

        assertNotEquals(
            key(first, authHeaders = mapOf("Cookie" to "session=one")),
            key(second, authHeaders = mapOf("Cookie" to "session=two")),
        )
    }

    @Test
    fun `a different authorization header is a different key`() {
        val first = ComicImageRequest(url = url, sourceId = sourceId)
        val second = ComicImageRequest(url = url, sourceId = sourceId)

        assertNotEquals(
            key(first, authHeaders = mapOf("Authorization" to "Bearer one")),
            key(second, authHeaders = mapOf("Authorization" to "Bearer two")),
        )
    }

    @Test
    fun `a header that cannot change the response does not split the cache`() {
        val withOneAgent = ComicImageRequest(
            url = url,
            sourceId = sourceId,
            headers = mapOf("User-Agent" to "Venera/1"),
        )
        val withAnotherAgent = ComicImageRequest(
            url = url,
            sourceId = sourceId,
            headers = mapOf("User-Agent" to "Venera/2", "Accept-Language" to "zh-CN"),
        )

        assertEquals(key(withOneAgent), key(withAnotherAgent))
    }

    @Test
    fun `a cookie reaches the key even though it is never stored in the request`() {
        val request = ComicImageRequest(url = url, sourceId = sourceId)

        assertNotEquals(key(request), key(request, authHeaders = mapOf("Cookie" to "session=one")))
    }

    @Test
    fun `GET and POST are different keys`() {
        val get = ComicImageRequest(url = url, sourceId = sourceId, method = ComicImageMethod.GET)
        val post = ComicImageRequest(
            url = url,
            sourceId = sourceId,
            method = ComicImageMethod.POST,
            body = ComicImageBody.Form(mapOf("id" to "3")),
        )

        assertNotEquals(key(get), key(post))
    }

    @Test
    fun `two POSTs with different bodies are different keys`() {
        val first = ComicImageRequest(
            url = url,
            sourceId = sourceId,
            method = ComicImageMethod.POST,
            body = ComicImageBody.Form(mapOf("id" to "3")),
        )
        val second = ComicImageRequest(
            url = url,
            sourceId = sourceId,
            method = ComicImageMethod.POST,
            body = ComicImageBody.Form(mapOf("id" to "4")),
        )

        assertNotEquals(key(first), key(second))
    }

    @Test
    fun `form fields are canonicalised so their order cannot split the cache`() {
        val first = ComicImageRequest(
            url = url,
            method = ComicImageMethod.POST,
            body = ComicImageBody.Form(linkedMapOf("a" to "1", "b" to "2")),
        )
        val second = ComicImageRequest(
            url = url,
            method = ComicImageMethod.POST,
            body = ComicImageBody.Form(linkedMapOf("b" to "2", "a" to "1")),
        )

        assertEquals(key(first), key(second))
    }

    @Test
    fun `the same url under two sources is two entries`() {
        val first = ComicImageRequest(url = url, sourceId = SourceId("source-a"))
        val second = ComicImageRequest(url = url, sourceId = SourceId("source-b"))

        assertNotEquals(key(first), key(second))
    }

    @Test
    fun `a different referer is a different key`() {
        val first = ComicImageRequest(url = url, sourceId = sourceId, referer = "https://a.example")
        val second = ComicImageRequest(url = url, sourceId = sourceId, referer = "https://b.example")

        assertNotEquals(key(first), key(second))
    }

    private fun key(
        request: ComicImageRequest,
        authHeaders: Map<String, String> = emptyMap(),
    ): String = ComicImageCacheKey.of(request, authHeaders)
}
