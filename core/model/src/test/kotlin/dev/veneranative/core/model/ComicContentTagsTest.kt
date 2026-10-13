package dev.veneranative.core.model

import org.junit.Assert.assertEquals
import org.junit.Test

class ComicContentTagsTest {
    @Test fun `content tags exclude author and status and normalize grouped and flat labels`() {
        val detail = ComicDetail(
            comic = Comic(ComicKey(SourceId("fixture"), RemoteComicId("comic")), "Fixture", tags = listOf("Adventure", " Fantasy ", "")),
            tagGroups = linkedMapOf(
                "作者" to listOf("Author"), "状态" to listOf("Ongoing"),
                "标签" to listOf(" Adventure ", ""), "Genres" to listOf("Fantasy"),
            ),
        )
        assertEquals(listOf("Adventure", "Fantasy"), detail.contentTags())
    }
}
