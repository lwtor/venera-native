package dev.veneranative.data.source

import java.net.URI
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SourceCatalogParserTest {
    private val catalog = URI("https://cdn.jsdelivr.net/gh/venera-app/venera-configs@main/index.json")

    @Test
    fun `parses current upstream fields and resolves script path against index`() {
        val entries = SourceCatalogParser.parse(
            """[{"name":"MangaDex","fileName":"manga_dex.js","key":"manga_dex","version":"1.2.0","description":"Public catalog source"}]""",
            catalog,
        )

        assertEquals(
            SourceCatalogEntry(
                name = "MangaDex",
                key = "manga_dex",
                version = "1.2.0",
                description = "Public catalog source",
                scriptUrl = "https://cdn.jsdelivr.net/gh/venera-app/venera-configs@main/manga_dex.js",
            ),
            entries.single(),
        )
    }

    @Test
    fun `supports the documented legacy filename and direct url fields`() {
        val entries = SourceCatalogParser.parse(
            """[
              {"name":"Relative","filename":"nested/source.js","version":"1"},
              {"name":"Direct","url":"https://example.org/comics.js","version":"2"}
            ]""",
            catalog,
        )

        assertEquals("https://cdn.jsdelivr.net/gh/venera-app/venera-configs@main/nested/source.js", entries[0].scriptUrl)
        assertEquals("https://example.org/comics.js", entries[1].scriptUrl)
    }

    @Test
    fun `skips malformed and insecure script entries`() {
        val entries = SourceCatalogParser.parse(
            """[
              {"name":"No script"},
              {"name":"Plain HTTP","url":"http://example.org/source.js"},
              {"name":"Usable","fileName":"usable.js"}
            ]""",
            catalog,
        )

        assertEquals(listOf("Usable"), entries.map { it.name })
    }

    @Test
    fun `duplicate script URLs appear only once in the catalog`() {
        val entries = SourceCatalogParser.parse(
            """[
              {"name":"First","fileName":"same.js"},
              {"name":"Duplicate","url":"https://cdn.jsdelivr.net/gh/venera-app/venera-configs@main/same.js"}
            ]""",
            catalog,
        )

        assertEquals(listOf("First"), entries.map { it.name })
    }

    @Test
    fun `rejects non-array or empty catalogs and non-HTTPS catalog addresses`() {
        assertThrows(IllegalArgumentException::class.java) {
            SourceCatalogParser.parse("{}", catalog)
        }
        assertThrows(IllegalStateException::class.java) {
            SourceCatalogParser.parse("[]", catalog)
        }
        assertThrows(IllegalArgumentException::class.java) {
            SourceCatalogParser.parse("[]", URI("http://example.org/index.json"))
        }
    }
}
