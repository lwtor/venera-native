package dev.veneranative.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalDirectoryScannerTest {
    private val scanner = LocalDirectoryScanner()
    @Test fun rootImagesAndSubdirectoriesBothBecomeChaptersAndCoverIsPreferred() {
        val root = TreeNode("tree://comic", "Comic", true, children = listOf(
            TreeNode("tree://comic/a", "10.jpg", false),
            TreeNode("tree://comic/b", "2.JPG", false),
            TreeNode("tree://comic/c", "cover.webp", false),
            TreeNode("tree://comic/t", "notes.txt", false),
            TreeNode("tree://comic/nested", "nested", true, children = listOf(TreeNode("tree://deep", "3.png", false))),
        ))
        val result = scanner.scan(root)
        assertEquals(listOf("Comic", "nested"), result.chapters.map { it.title })
        assertEquals(listOf("2.JPG", "10.jpg"), result.chapters.first().pages.map { it.name })
        assertEquals(listOf("3.png"), result.chapters.last().pages.map { it.name })
        assertEquals("tree://comic/c", result.coverUri)
    }
    @Test fun coverOnlyRootStillFindsSubdirectoryChapters() {
        val result = scanner.scan(TreeNode("tree://comic", "Comic", true, children = listOf(
            TreeNode("tree://comic/cover", "cover.webp", false),
            TreeNode("tree://comic/chapter", "Chapter 1", true, children = listOf(
                TreeNode("tree://comic/chapter/page", "1.png", false),
            )),
        )))
        assertEquals(listOf("Chapter 1"), result.chapters.map { it.title })
        assertEquals("tree://comic/cover", result.coverUri)
    }
    @Test fun foldersBecomeNaturallyOrderedChapters() {
        val result = scanner.scan(TreeNode("tree://c", "Series", true, children = listOf(
            TreeNode("tree://c/10", "10", true, children = listOf(TreeNode("tree://c/10/001.png", "001.png", false, 10))),
            TreeNode("tree://c/2", "2", true, children = listOf(TreeNode("tree://c/2/001.png", "001.png", false, 10))),
            TreeNode("tree://c/empty", "Empty", true),
        )))
        assertEquals(listOf("2", "10"), result.chapters.map { it.title })
        assertEquals(2, result.chapters.sumOf { it.pages.size })
        assertTrue(result.coverUri!!.endsWith("001.png"))
    }
}
