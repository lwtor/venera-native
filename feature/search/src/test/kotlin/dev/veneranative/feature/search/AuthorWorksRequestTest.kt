package dev.veneranative.feature.search

import dev.veneranative.core.model.ComicKey
import dev.veneranative.core.model.FilterOption
import dev.veneranative.core.model.RemoteComicId
import dev.veneranative.core.model.SourceFilter
import dev.veneranative.core.model.SourceId
import dev.veneranative.core.model.TagNavigation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AuthorWorksRequestTest {
    private val origin = ComicKey(SourceId("source"), RemoteComicId("comic"))
    private val filters = listOf(SourceFilter.Select("order", "Order", listOf(FilterOption("new", "New"), FilterOption("old", "Old"))))
    @Test fun `author search retains the source transformation and explicit options`() {
        val request = authorWorksRequest(origin, TagNavigation.Search("作者:A", listOf("author")), filters)
        assertNull(request.second)
        assertEquals(origin.sourceId, request.first!!.sourceId)
        assertEquals("作者:A", request.first!!.keyword)
        assertEquals(listOf("author"), request.first!!.optionsOverride)
    }
    @Test fun `category uses source defaults unless callback overrides them`() {
        val defaults = authorWorksRequest(origin, TagNavigation.Category("A", "author-id"), filters).second!!
        assertEquals("author-id", defaults.param)
        assertEquals(listOf("new"), defaults.options)
        assertEquals(listOf("old"), authorWorksRequest(origin, TagNavigation.Category("A", options = listOf("old")), filters).second!!.options)
        assertEquals(listOf("opaque"), authorWorksRequest(origin, TagNavigation.Category("A", options = listOf("opaque")), emptyList()).second!!.options)
    }
    @Test fun `exact author identity survives result request construction`() {
        val request = authorWorksRequest(origin, TagNavigation.Search("A", authorId = "author-7"), emptyList()).first!!
        assertEquals("author-7", request.authorId)
    }
}
