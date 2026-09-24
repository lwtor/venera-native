package dev.veneranative.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LocalCoverResolverTest {
    @Test fun namedCoverWinsOverFirstPage() {
        val cover = TreeNode("tree://cover", "Cover.JPG", false)
        val page = ScannedPage("tree://page", "001.jpg", 10)
        assertEquals("tree://cover", LocalCoverResolver.resolve(listOf(pageNode(), cover), listOf(chapter(page))))
    }
    @Test fun firstPageIsFallbackAndEmptyTreesHaveNoCover() {
        val page = ScannedPage("tree://page", "001.jpg", 10)
        assertEquals("tree://page", LocalCoverResolver.resolve(emptyList(), listOf(chapter(page))))
        assertNull(LocalCoverResolver.resolve(emptyList(), emptyList()))
    }
    private fun pageNode() = TreeNode("tree://page", "001.jpg", false)
    private fun chapter(page: ScannedPage) = ScannedChapter("ch", "Chapter", null, listOf(page))
}
