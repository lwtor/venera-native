package dev.veneranative.source.api.protocol

import dev.veneranative.core.model.ChapterKey
import dev.veneranative.core.model.ComicKey
import dev.veneranative.core.model.ExploreItem
import dev.veneranative.core.model.ExploreKind
import dev.veneranative.core.model.ExplorePage
import dev.veneranative.core.model.FilterOption
import dev.veneranative.core.model.FilterSelection
import dev.veneranative.core.model.PageCursor
import dev.veneranative.core.model.RemoteChapterId
import dev.veneranative.core.model.RemoteComicId
import dev.veneranative.core.model.SourceFilter
import dev.veneranative.core.model.SourceId
import dev.veneranative.source.api.ExploreRequest
import dev.veneranative.source.api.SearchRequest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Encoder checks for the upstream call shapes.
 *
 * The argument array is what a real source sees, so these assertions are the compatibility contract
 * (ADR-0007 §2.3 and §4.3) rather than an implementation detail.
 */
class SourceProtocolTest {

    private val sourceId = SourceId("source-a")
    private val comicKey = ComicKey(sourceId, RemoteComicId("comic-1"))
    private val chapterKey = ChapterKey(comicKey, RemoteChapterId("chapter-1"))

    private val sort = SourceFilter.Select(
        key = "sort",
        label = "sort",
        options = listOf(FilterOption("0", "time"), FilterOption("1", "popular")),
        defaultValue = "0",
    )

    private val genres = SourceFilter.MultiSelect(
        key = "genre",
        label = "genre",
        options = listOf(FilterOption("action", "Action"), FilterOption("comedy", "Comedy")),
    )

    @Test
    fun `loadInfo passes the comic id`() {
        val call = SourceProtocol.loadInfo(comicKey)

        assertEquals(SourceProtocol.MEMBER_LOAD_INFO, call.member)
        // Pinned literally: upstream reaches details through `comic`, not at the top level.
        assertEquals("comic.loadInfo", call.member)
        assertEquals(listOf("comic-1"), call.arguments().map { it.jsonPrimitive.content })
    }

    @Test
    fun `loadEp passes the comic id and the chapter id`() {
        val call = SourceProtocol.loadEp(chapterKey)

        assertEquals(SourceProtocol.MEMBER_LOAD_EP, call.member)
        assertEquals("comic.loadEp", call.member)
        assertEquals(listOf("comic-1", "chapter-1"), call.arguments().map { it.jsonPrimitive.content })
    }

    @Test
    fun `search options are positional and a multi select stays a json string`() {
        val request = SearchRequest(
            sourceId = sourceId,
            keyword = "frieren",
            cursor = PageCursor.Page(2),
            filters = FilterSelection(mapOf("sort" to listOf("1"), "genre" to listOf("comedy", "action"))),
        )

        val call = SourceProtocol.searchLoad(listOf(sort, genres), request, pageNumber = 2)
        val arguments = call.arguments()

        assertEquals(SourceProtocol.MEMBER_SEARCH_LOAD, call.member)
        assertEquals("frieren", arguments[0].jsonPrimitive.content)
        assertEquals(2, arguments[2].jsonPrimitive.int)
        val options = arguments[1].jsonArray
        assertEquals("1", options[0].jsonPrimitive.content)
        assertEquals("""["comedy","action"]""", options[1].jsonPrimitive.content)
    }

    @Test
    fun `searchLoad sends the page number the caller decided on`() {
        val call = SourceProtocol.searchLoad(
            filters = listOf(sort),
            request = SearchRequest(sourceId = sourceId, keyword = "x"),
            pageNumber = 1,
        )

        assertEquals(SourceProtocol.MEMBER_SEARCH_LOAD, call.member)
        assertEquals(1, call.arguments()[2].jsonPrimitive.int)
    }

    @Test
    fun `searchLoadNext sends the previous token`() {
        val call = SourceProtocol.searchLoadNext(
            filters = emptyList(),
            request = SearchRequest(sourceId = sourceId, keyword = "x"),
            token = "t1",
        )

        assertEquals(SourceProtocol.MEMBER_SEARCH_LOAD_NEXT, call.member)
        assertEquals("t1", call.arguments()[2].jsonPrimitive.content)
    }

    @Test
    fun `the first cursor page is sent as null`() {
        val call = SourceProtocol.searchLoadNext(
            filters = emptyList(),
            request = SearchRequest(sourceId = sourceId, keyword = "x"),
            token = null,
        )

        assertEquals(JsonNull, call.arguments()[2])
    }

    @Test
    fun `an unselected dropdown is sent as null`() {
        val status = SourceFilter.Dropdown(
            key = "status",
            label = "status",
            options = listOf(FilterOption("done", "Done")),
        )

        val call = SourceProtocol.searchLoad(
            filters = listOf(status),
            request = SearchRequest(sourceId = sourceId, keyword = "x"),
            pageNumber = 1,
        )

        assertEquals(JsonNull, call.arguments()[1].jsonArray[0])
    }

    @Test
    fun `explore page numbers follow the page kind`() {
        val popular = ExplorePage.of("Popular", ExploreKind.MULTI_PAGE)
        val mixed = ExplorePage.of("Frontpage", ExploreKind.MIXED)
        val sections = ExplorePage.of("Sections", ExploreKind.MULTI_PART)

        val firstPage = SourceProtocol.explore(popular, ExploreRequest(sourceId, popular.key))
        val thirdPage = SourceProtocol.explore(
            popular,
            ExploreRequest(sourceId, popular.key, cursor = PageCursor.Page(3)),
        )
        val mixedFirst = SourceProtocol.explore(mixed, ExploreRequest(sourceId, mixed.key))
        val singlePage = SourceProtocol.explore(sections, ExploreRequest(sourceId, sections.key))

        // The page is identified by position, so the page argument is the only argument.
        assertEquals(1, firstPage.arguments()[0].jsonPrimitive.int)
        assertEquals(3, thirdPage.arguments()[0].jsonPrimitive.int)
        assertEquals(0, mixedFirst.arguments()[0].jsonPrimitive.int)
        assertEquals(JsonNull, singlePage.arguments()[0])
        assertEquals(1, firstPage.arguments().size)
    }

    @Test
    fun `the explore member is addressed by page key`() {
        val page = ExplorePage.of("Popular", ExploreKind.MULTI_PAGE)

        val call = SourceProtocol.explore(page, ExploreRequest(sourceId, page.key))

        assertEquals(SourceProtocol.MEMBER_EXPLORE_LOAD, call.member)
    }

    private fun SourceProtocolCall.arguments() = Json.parseToJsonElement(argumentsJson).jsonArray
}
