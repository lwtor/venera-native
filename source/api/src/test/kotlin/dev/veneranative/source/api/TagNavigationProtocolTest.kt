package dev.veneranative.source.api

import dev.veneranative.core.model.PageCursor
import dev.veneranative.core.model.SourceId
import dev.veneranative.core.model.TagNavigation
import dev.veneranative.source.api.protocol.SourceProtocol
import dev.veneranative.source.api.protocol.SourceProtocolParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TagNavigationProtocolTest {
    @Test fun `legacy author search preserves source prefix`() {
        assertEquals(TagNavigation.Search("作者:作者甲"), SourceProtocolParser.parseTagNavigation("""{"action":"search","keyword":"作者:作者甲"}"""))
        assertEquals(TagNavigation.Category("作者甲", "id:7"), SourceProtocolParser.parseTagNavigation("""{"action":"category","keyword":"作者甲","param":"id:7"}"""))
    }
    @Test fun `modern target preserves attributes and positional options`() {
        assertEquals(TagNavigation.Search("artist:A", listOf("recent", "")), SourceProtocolParser.parseTagNavigation("""{"page":"search","attributes":{"text":"artist:A","options":["recent",""]}}"""))
        assertEquals(TagNavigation.Category("A", "author-id", listOf("desc")), SourceProtocolParser.parseTagNavigation("""{"page":"category","attributes":{"category":"A","param":"author-id","options":["desc"]}}"""))
        assertEquals(TagNavigation.Search("A"), SourceProtocolParser.parseTagNavigation("""{"page":"search","attributes":{"keyword":"A"}}"""))
    }
    @Test fun `no target or unsupported page is not guessed into a keyword search`() {
        listOf("null", "{}", """{"action":"search","keyword":""}""", """{"page":"web","attributes":{"url":"https://fixture.invalid"}}""").forEach {
            assertNull(SourceProtocolParser.parseTagNavigation(it))
        }
    }
    @Test fun `category arguments retain param options and one based page`() {
        val request = CategoryRequest(SourceId("fixture"), "A", "id", listOf("desc"), PageCursor.Page(2))
        assertEquals("categoryComics.load", SourceProtocol.category(request).member)
        assertEquals("""["A","id",["desc"],2]""", SourceProtocol.category(request).argumentsJson)
        assertEquals("""["作者","A\"B"]""", SourceProtocol.onClickTag("作者", "A\"B").argumentsJson)
        assertEquals("""["artist:A",["new"],1]""", SourceProtocol.searchLoad(emptyList(), SearchRequest(SourceId("fixture"), "artist:A", optionsOverride = listOf("new")), 1).argumentsJson)
    }
}
