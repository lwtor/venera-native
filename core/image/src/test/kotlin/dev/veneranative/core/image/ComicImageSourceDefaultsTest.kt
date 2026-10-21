package dev.veneranative.core.image

import dev.veneranative.core.model.SourceId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ComicImageSourceDefaultsTest {
    @Test
    fun `ikmmh cover requests use the source thumbnail headers`() {
        val headers = ComicImageSourceDefaults.headersFor(SourceId("ikmmh"))

        assertEquals("https://www.ikmmh.com", headers["Referer"])
        assertEquals("Mozilla/5.0 (iPhone; CPU iPhone OS 18_5 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.5 Mobile/15E148 Safari/604.1", headers["User-Agent"])
        assertTrue(headers.getValue("Accept").contains("image/webp"))
    }

    @Test
    fun `other sources do not inherit ikmmh headers`() {
        assertTrue(ComicImageSourceDefaults.headersFor(SourceId("copy_manga")).isEmpty())
        assertTrue(ComicImageSourceDefaults.headersFor(null).isEmpty())
    }
}
