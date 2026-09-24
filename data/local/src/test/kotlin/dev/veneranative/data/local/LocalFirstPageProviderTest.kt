package dev.veneranative.data.local

import dev.veneranative.core.model.ChapterContent
import dev.veneranative.core.model.ChapterKey
import dev.veneranative.core.model.ChapterRef
import dev.veneranative.core.model.ComicKey
import dev.veneranative.core.model.LocalChapterId
import dev.veneranative.core.model.LocalComicId
import dev.veneranative.core.model.RemoteChapterId
import dev.veneranative.core.model.RemoteComicId
import dev.veneranative.core.model.SourceId
import dev.veneranative.core.model.PageProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.nio.file.Files

class LocalFirstPageProviderTest {
    @Test fun localKeyLoadsIndexedPagesWithoutSourceIdentity() = kotlinx.coroutines.runBlocking {
        val comicId = LocalComicId("imported")
        val chapterId = LocalChapterId("chapter-1")
        val repository = FakeLocalRepository(comicId, chapterId)
        val root = Files.createTempDirectory("materialized-pages").toFile()
        var opened = 0
        val materializer = LocalPageMaterializer(
            LocalPageCache(root), LocalPageSource { opened++; ByteArrayInputStream(pngHeader()) },
        )
        val remote = object : PageProvider {
            override suspend fun loadChapter(chapter: ChapterRef) = error("local chapter must not reach source")
        }
        val provider = LocalFirstPageProvider(remote, { repository }, materializer)
        val content = provider.loadChapter(localReaderKey(comicId, chapterId))
        assertEquals("Local chapter", content.title)
        assertEquals(1, content.pages.size)
        assertNull(content.pages.single().sourceId)
        assertEquals(0, opened)
        assertFalse(java.io.File(content.pages.single().imageRef).isFile)
        val resolved = provider.resolve(content.pages.single())
        assertEquals(1, opened)
        assertTrue(java.io.File(resolved.imageRef).isFile)
        assertEquals(8, resolved.widthPx)
        assertEquals(12, resolved.heightPx)
        root.deleteRecursively()
        Unit
    }

    private fun pngHeader(): ByteArray = byteArrayOf(
        0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a,
        0, 0, 0, 13, 0x49, 0x48, 0x44, 0x52,
        0, 0, 0, 8, 0, 0, 0, 12,
    )

    @Test fun remoteKeyContinuesThroughTheExistingOfflineSourceProvider() = kotlinx.coroutines.runBlocking {
        val expected = ChapterContent("Remote", emptyList())
        val source = object : PageProvider {
            override suspend fun loadChapter(chapter: ChapterRef) = expected
        }
        val root = Files.createTempDirectory("unused-page-cache").toFile()
        val local = LocalFirstPageProvider(source, { error("remote chapter must not open local repository") },
            LocalPageMaterializer(LocalPageCache(root), LocalPageSource { error("remote chapter has no local page") }))
        val remote = ChapterRef.Remote(ChapterKey(ComicKey(SourceId("source"), RemoteComicId("comic")), RemoteChapterId("chapter")))
        assertEquals(expected, local.loadChapter(remote))
        root.deleteRecursively()
        Unit
    }

    @Test fun evictedLocalPageCanBeMaterializedAgainWhenRevisited() = kotlinx.coroutines.runBlocking {
        val comicId = LocalComicId("imported")
        val chapterId = LocalChapterId("chapter-1")
        val root = Files.createTempDirectory("bounded-page-cache").toFile()
        var opened = 0
        val provider = LocalFirstPageProvider(
            object : PageProvider {
                override suspend fun loadChapter(chapter: ChapterRef) = error("not remote")
            },
            { FakeLocalRepository(comicId, chapterId, pageCount = 2) },
            LocalPageMaterializer(LocalPageCache(root, maxBytes = 32, maxEntryBytes = 32),
                LocalPageSource { opened++; ByteArrayInputStream(pngHeader()) }),
        )
        val pages = provider.loadChapter(localReaderKey(comicId, chapterId)).pages
        val first = provider.resolve(pages[0])
        provider.resolve(pages[1])
        assertFalse(java.io.File(first.imageRef).isFile)

        val revisited = provider.resolve(first)

        assertTrue(java.io.File(revisited.imageRef).isFile)
        assertEquals(3, opened)
        root.deleteRecursively()
        Unit
    }

    private class FakeLocalRepository(
        private val comic: LocalComicId,
        private val chapter: LocalChapterId,
        private val pageCount: Int = 1,
    ) : LocalComicRepository {
        override fun observeComics(): Flow<List<LocalComic>> = flowOf(listOf(LocalComic(comic, "Local comic", LocalKind.Directory, "root", null, 1, 0)))
        override fun observeChapters(comicId: LocalComicId): Flow<List<LocalChapter>> = flowOf(listOf(LocalChapter(chapter, comic, "Local chapter", 0)))
        override suspend fun pages(comicId: LocalComicId, chapterId: LocalChapterId) =
            List(pageCount) { index -> LocalPage(comic, chapter, index, "page-uri-$index", "$index.jpg", 24, "root") }
        override suspend fun importTree(uri: String) = LocalImportResult.Empty
        override suspend fun importArchive(uri: String) = LocalImportResult.Empty
        override suspend fun remove(comicId: LocalComicId) = Unit
        override suspend fun refresh(comicId: LocalComicId) = Unit
        override suspend fun grants() = emptyList<SafGrant>()
        override suspend fun releaseGrant(uri: String) = Unit
    }
}
