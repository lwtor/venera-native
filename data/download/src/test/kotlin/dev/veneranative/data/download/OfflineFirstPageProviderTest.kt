package dev.veneranative.data.download

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import dev.veneranative.core.model.ChapterContent
import dev.veneranative.core.model.ChapterKey
import dev.veneranative.core.model.ComicPage
import dev.veneranative.core.model.PageProvider

class OfflineFirstPageProviderTest {
    @get:Rule val folder = TemporaryFolder()
    private val chapterRef = remoteChapter()
    private val chapter = (chapterRef as dev.veneranative.core.model.ChapterRef.Remote).key

    @Test fun `complete downloaded chapter is served from disk without a source id`() = runTest {
        val layout = DownloadFileLayout(folder.root)
        val dao = FakeDownloadDao()
        val file = layout.pageFile(refSourceOf(chapterRef), refComicOf(chapterRef), refChapterOf(chapterRef), 0)
        val bytes = pngBytes(640, 960)
        layout.writeAtomically(file, bytes)
        dao.tasks.value = listOf(taskEntity(chapterRef, state = DownloadChapterState.Completed, pageCount = 1))
        dao.pages.value = listOf(pageEntity(chapterRef.taskId(), 0, DownloadPageState.Succeeded, layout.relativeOf(file), bytes.size.toLong()))
        val source = RecordingPageProvider()
        val provider = OfflineFirstPageProvider({ DefaultDownloadRepository(dao, layout) }, layout, source)

        val content = provider.loadChapter(chapter)
        val resolved = provider.resolve(content.pages.single())

        assertEquals("Chapter 1", content.title)
        assertEquals(file.absolutePath, content.pages.single().imageRef)
        assertNull(content.pages.single().sourceId)
        assertEquals(640, resolved.widthPx)
        assertEquals(960, resolved.heightPx)
        assertEquals(0, source.calls)
    }

    @Test fun `incomplete download falls back to the source provider`() = runTest {
        val layout = DownloadFileLayout(folder.root)
        val source = RecordingPageProvider()
        val provider = OfflineFirstPageProvider({ DefaultDownloadRepository(FakeDownloadDao(), layout) }, layout, source)

        val content = provider.loadChapter(chapter)

        assertEquals("Online", content.title)
        assertEquals(1, source.calls)
    }

    private class RecordingPageProvider : PageProvider {
        var calls = 0
        override suspend fun loadChapter(chapter: ChapterKey): ChapterContent {
            calls++
            return ChapterContent("Online", emptyList())
        }
        override suspend fun resolve(page: ComicPage): ComicPage = page
    }
}
