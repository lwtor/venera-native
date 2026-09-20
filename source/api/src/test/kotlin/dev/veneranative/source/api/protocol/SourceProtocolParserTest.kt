package dev.veneranative.source.api.protocol

import dev.veneranative.core.model.ComicKey
import dev.veneranative.core.model.ExploreItem
import dev.veneranative.core.model.ExploreKind
import dev.veneranative.core.model.ExplorePage
import dev.veneranative.core.model.PageCursor
import dev.veneranative.core.model.RemoteComicId
import dev.veneranative.core.model.SourceId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Parser checks against the response shapes a real source produces (ADR-0007 §4).
 *
 * Two behaviours matter as much as the field mapping: a malformed entry must not fail the whole
 * page, and the grouped chapter shape must not be mistaken for the flat one — that mistake creates
 * a phantom chapter out of the latest-chapter marker.
 */
class SourceProtocolParserTest {

    private val sourceId = SourceId("source-a")
    private val comicKey = ComicKey(sourceId, RemoteComicId("comic-1"))

    @Test
    fun `a comic list keeps valid entries and reports the next page`() {
        val payload = """
            {"comics":[
              {"id":"c1","title":"A","subTitle":"Author","cover":"cover.jpg","tags":["x"],"maxPage":3},
              {"title":"missing id"},
              {"id":"c2","title":"B"}
            ],"maxPage":2}
        """.trimIndent()

        val page = SourceProtocolParser.parseComicList(sourceId, payload, pageNumber = 1)

        assertEquals(listOf("A", "B"), page.items.map { it.title })
        assertEquals("Author", page.items.first().subtitle)
        assertEquals(listOf("x"), page.items.first().tags)
        assertEquals(3, page.items.first().maxPage)
        assertEquals(2, page.totalPages)
        assertEquals(PageCursor.Page(2), page.next)
    }

    @Test
    fun `the last page of a comic list has no next cursor`() {
        val page = SourceProtocolParser.parseComicList(
            sourceId,
            """{"comics":[{"id":"c1","title":"A"}],"maxPage":2}""",
            pageNumber = 2,
        )

        assertNull(page.next)
        assertTrue(!page.hasMore)
    }

    @Test
    fun `a malformed payload yields an empty page instead of throwing`() {
        val page = SourceProtocolParser.parseComicList(sourceId, "[]", pageNumber = 1)

        assertTrue(page.items.isEmpty())
        assertNull(page.next)
    }

    @Test
    fun `grouped chapters keep groups and ignore the latest chapter marker`() {
        val payload = """
            {"title":"T","description":"d",
             "tags":{"Tags":["Action"],"Authors":["Someone"]},
             "chapters":{
               "Volume 1 - EN":{"ch1":"Chapter 1"},
               "Volume 1 - CN":{"ch2":"第 2 话","ch3":"第 3 话"},
               "latestChapterMarker":"2024-01-01|ch3|cn"
             }}
        """.trimIndent()

        val detail = SourceProtocolParser.parseComicDetail(comicKey, payload)

        requireNotNull(detail)
        assertEquals(listOf("Chapter 1", "第 2 话", "第 3 话"), detail.chapters.map { it.title })
        assertEquals(
            listOf("Volume 1 - EN", "Volume 1 - CN", "Volume 1 - CN"),
            detail.chapters.map { it.group },
        )
        assertEquals(listOf(0, 1, 2), detail.chapters.map { it.index })
        assertEquals(listOf("Action", "Someone"), detail.comic.tags)
        assertEquals("d", detail.description)
        assertEquals(comicKey, detail.comic.key)
    }

    @Test
    fun `flat chapters keep the source order`() {
        val payload = """{"title":"T","chapters":{"ch2":"Chapter 2","ch1":"Chapter 1"}}"""

        val detail = SourceProtocolParser.parseComicDetail(comicKey, payload)

        requireNotNull(detail)
        assertEquals(listOf("Chapter 2", "Chapter 1"), detail.chapters.map { it.title })
        assertEquals(listOf(null, null), detail.chapters.map { it.group })
    }

    @Test
    fun `a detail without chapters is still valid`() {
        val detail = SourceProtocolParser.parseComicDetail(comicKey, """{"title":"T"}""")

        requireNotNull(detail)
        assertTrue(detail.chapters.isEmpty())
        assertNull(detail.comic.coverUrl)
    }

    @Test
    fun `a detail without a title is rejected rather than replaced by a placeholder`() {
        assertNull(SourceProtocolParser.parseComicDetail(comicKey, """{"description":"d"}"""))
    }

    @Test
    fun `images become indexed page references`() {
        val pages = SourceProtocolParser.parseImages("""{"images":["u1","u2","u3"]}""")

        assertEquals(listOf(0, 1, 2), pages.map { it.index })
        assertEquals(listOf("u1", "u2", "u3"), pages.map { it.imageRef })
    }

    @Test
    fun `a chapter without images yields no pages`() {
        assertTrue(SourceProtocolParser.parseImages("{}").isEmpty())
    }

    @Test
    fun `a multi part explore page becomes sections with an opaque view more`() {
        val payload = """
            [{"title":"Popular","comics":[{"id":"c1","title":"A"}],
              "viewMore":{"page":"search","attributes":{"options":["popular"]}}},
             {"title":"Recent","comics":[{"id":"c2","title":"B"}]}]
        """.trimIndent()

        val result = SourceProtocolParser.parseExplorePage(
            sourceId = sourceId,
            page = ExplorePage.of("Sections", ExploreKind.MULTI_PART),
            payload = payload,
            pageNumber = 1,
        )

        assertEquals(2, result.items.size)
        val first = result.items.first() as ExploreItem.Section
        assertEquals("Popular", first.title)
        assertEquals(listOf("A"), first.comics.map { it.title })
        assertTrue(first.viewMore!!.contains("search"))
        // A single-page kind never paginates.
        assertNull(result.next)
    }

    @Test
    fun `a mixed explore page accepts both plain lists and sections`() {
        val payload = """
            {"data":[
              [{"id":"c1","title":"A"}],
              {"title":"Weekly","comics":[{"id":"c2","title":"B"}]}
            ],"maxPage":3}
        """.trimIndent()

        val result = SourceProtocolParser.parseExplorePage(
            sourceId = sourceId,
            page = ExplorePage.of("Frontpage", ExploreKind.MIXED),
            payload = payload,
            pageNumber = 0,
        )

        assertEquals(2, result.items.size)
        assertTrue(result.items[0] is ExploreItem.Comics)
        assertEquals("Weekly", (result.items[1] as ExploreItem.Section).title)
        assertEquals(PageCursor.Page(1), result.next)
    }

    @Test
    fun `a page numbered explore page becomes one comic list`() {
        val payload = """{"comics":[{"id":"c1","title":"A"}],"maxPage":4}"""

        val result = SourceProtocolParser.parseExplorePage(
            sourceId = sourceId,
            page = ExplorePage.of("Popular", ExploreKind.MULTI_PAGE),
            payload = payload,
            pageNumber = 1,
        )

        assertEquals(1, result.items.size)
        assertEquals(listOf("A"), (result.items.single() as ExploreItem.Comics).comics.map { it.title })
        assertEquals(PageCursor.Page(2), result.next)
    }
}
