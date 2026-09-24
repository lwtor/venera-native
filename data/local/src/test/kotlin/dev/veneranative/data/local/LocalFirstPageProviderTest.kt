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
import org.junit.Test
import java.io.ByteArrayInputStream
import java.nio.file.Files

class LocalFirstPageProviderTest {
    @Test fun localKeyLoadsIndexedPagesWithoutSourceIdentity() = kotlinx.coroutines.runBlocking {
        val comicId = LocalComicId("imported")
        val chapterId = LocalChapterId("chapter-1")
        val repository = FakeLocalRepository(comicId, chapterId)
        val root = Files.createTempDirectory("materialized-pages").toFile()
        val materializer = LocalPageMaterializer(
            LocalPageCache(root), LocalPageSource { ByteArrayInputStream(byteArrayOf(1, 2, 3)) },
        )
        val remote = object : PageProvider {
            override suspend fun loadChapter(chapter: ChapterRef) = error("local chapter must not reach source")
        }
        val provider = LocalFirstPageProvider(remote, { repository }, materializer)
        val content = provider.loadChapter(localReaderKey(comicId, chapterId))
        assertEquals("Local chapter", content.title)
        assertEquals(1, content.pages.size)
        assertNull(content.pages.single().sourceId)
        assertEquals(true, java.io.File(content.pages.single().imageRef).isFile)
        root.deleteRecursively()
        Unit
    }

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

    private class FakeLocalRepository(private val comic: LocalComicId, private val chapter: LocalChapterId) : LocalComicRepository {
        override fun observeComics(): Flow<List<LocalComic>> = flowOf(listOf(LocalComic(comic, "Local comic", LocalKind.Directory, "root", null, 1, 0)))
        override fun observeChapters(comicId: LocalComicId): Flow<List<LocalChapter>> = flowOf(listOf(LocalChapter(chapter, comic, "Local chapter", 0)))
        override suspend fun pages(comicId: LocalComicId, chapterId: LocalChapterId) = listOf(LocalPage(comic, chapter, 0, "page-uri", "1.jpg", 3, "root"))
        override suspend fun importTree(uri: String) = LocalImportResult.Empty
        override suspend fun importArchive(uri: String) = LocalImportResult.Empty
        override suspend fun remove(comicId: LocalComicId) = Unit
        override suspend fun refresh(comicId: LocalComicId) = Unit
        override suspend fun grants() = emptyList<SafGrant>()
        override suspend fun releaseGrant(uri: String) = Unit
    }
}
