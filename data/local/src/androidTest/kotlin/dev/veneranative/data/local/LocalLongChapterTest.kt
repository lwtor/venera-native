package dev.veneranative.data.local

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.veneranative.core.model.ChapterRef
import dev.veneranative.core.model.LocalChapterId
import dev.veneranative.core.model.LocalComicId
import dev.veneranative.core.model.PageProvider
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Device regression for large local chapters: indexing stays lazy and evicted pages reopen. */
@RunWith(AndroidJUnit4::class)
class LocalLongChapterTest {
    @Test
    fun indexesOver256MiBWithoutMaterializingAndReopensEvictedFirstPage() = runBlocking {
        val comicId = LocalComicId("d06-long-chapter")
        val chapterId = LocalChapterId("large-chapter")
        val repository = LargeChapterRepository(comicId, chapterId)
        val cacheRoot = Files.createTempDirectory("venera-d06-local-pages").toFile()
        val opened = mutableMapOf<String, Int>()
        val cache = LocalPageCache(cacheRoot)
        val provider = LocalFirstPageProvider(
            source = object : PageProvider {
                override suspend fun loadChapter(chapter: ChapterRef) = error("local pages must stay local")
            },
            localRepository = { repository },
            materializer = LocalPageMaterializer(cache, LocalPageSource { page ->
                opened[page.uri] = (opened[page.uri] ?: 0) + 1
                PaddedPngInputStream(PAGE_BYTES)
            }),
        )

        try {
            val content = provider.loadChapter(localReaderKey(comicId, chapterId))
            assertEquals(PAGE_COUNT, content.pages.size)
            assertEquals((PAGE_COUNT * PAGE_BYTES).toLong(), repository.pages(comicId, chapterId).sumOf { it.sizeBytes })
            assertTrue("chapter indexing must not extract page files", cacheRoot.listFiles().orEmpty().isEmpty())

            val firstResolved = provider.resolve(content.pages.first())
            content.pages.drop(1).forEach { provider.resolve(it) }

            val firstFile = File(firstResolved.imageRef)
            assertFalse("the first page should have been evicted after the cache exceeded 256 MiB", firstFile.exists())
            val revisited = provider.resolve(firstResolved)
            assertTrue(File(revisited.imageRef).isFile)
            assertEquals("the evicted page must be read from its local source again", 2, opened.getValue("page-0"))
            assertTrue(cacheRoot.listFiles().orEmpty().sumOf { it.length() } <= CACHE_BYTES)
        } finally {
            cacheRoot.deleteRecursively()
        }
        Unit
    }

    private class LargeChapterRepository(
        private val comicId: LocalComicId,
        private val chapterId: LocalChapterId,
    ) : LocalComicRepository {
        override fun observeComics(): Flow<List<LocalComic>> = flowOf(
            listOf(LocalComic(comicId, "D06 synthetic long chapter", LocalKind.Directory, "d06", null, 1, 0)),
        )

        override fun observeChapters(comicId: LocalComicId): Flow<List<LocalChapter>> {
            return flowOf(listOf(LocalChapter(chapterId, comicId, "Large chapter", 0)))
        }

        override suspend fun pages(comicId: LocalComicId, chapterId: LocalChapterId) =
            List(PAGE_COUNT) { index ->
                LocalPage(comicId, chapterId, index, "page-$index", "$index.png", PAGE_BYTES.toLong(), "d06")
            }

        override suspend fun importTree(uri: String) = LocalImportResult.Empty
        override suspend fun importArchive(uri: String) = LocalImportResult.Empty
        override suspend fun remove(comicId: LocalComicId) = Unit
        override suspend fun refresh(comicId: LocalComicId) = Unit
        override suspend fun grants() = emptyList<SafGrant>()
        override suspend fun releaseGrant(uri: String) = Unit
    }

    /** Emits a valid PNG header followed by padding, so the test exercises file volume, not RAM. */
    private class PaddedPngInputStream(private val totalBytes: Int) : InputStream() {
        private val header = byteArrayOf(
            0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a,
            0, 0, 0, 13, 0x49, 0x48, 0x44, 0x52,
            0, 0, 0, 8, 0, 0, 0, 12,
        )
        private var position = 0

        override fun read(): Int = if (position >= totalBytes) -1 else {
            val value = if (position < header.size) header[position].toInt() and 0xff else 0
            position++
            value
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (position >= totalBytes) return -1
            val count = minOf(length, totalBytes - position)
            buffer.fill(0, offset, offset + count)
            val headerCount = minOf(count, (header.size - position).coerceAtLeast(0))
            if (headerCount > 0) header.copyInto(buffer, offset, position, position + headerCount)
            position += count
            return count
        }
    }

    private companion object {
        const val PAGE_COUNT = 257
        const val PAGE_BYTES = 1024 * 1024
        const val CACHE_BYTES = 256L * 1024 * 1024
    }
}
