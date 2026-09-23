package dev.veneranative.data.download

import java.io.IOException
import java.io.OutputStream
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class PageDownloaderTest {

    @get:Rule val folder = TemporaryFolder()

    private val target = DownloadTarget(
        sourceId = "manga_dex",
        comicId = "frieren",
        chapterId = "ch-1",
        index = 4,
        imageRef = "https://cdn.test/4.png",
    )

    private fun downloader(
        body: ByteArray? = pngBytes(800, 1200),
        failure: Throwable? = null,
    ): Pair<PageDownloader, MutableList<PageFetchRequest>> {
        val seen = mutableListOf<PageFetchRequest>()
        val source = object : PageByteSource {
            override suspend fun fetch(request: PageFetchRequest, sink: OutputStream): Long? {
                seen += request
                failure?.let { throw it }
                val bytes = body ?: return null
                sink.write(bytes)
                return bytes.size.toLong()
            }
        }
        return PageDownloader(DownloadFileLayout(folder.root), source) to seen
    }

    @Test
    fun `a real image is written, kept and reported with its size`() = runTest {
        val (downloader, seen) = downloader()

        val result = downloader.download(target)

        val success = result as PageDownloadResult.Succeeded
        assertEquals(pngBytes(800, 1200).size.toLong(), success.bytes)
        assertEquals(800, success.widthPx)
        assertEquals(1200, success.heightPx)
        assertTrue(DownloadFileLayout(folder.root).absoluteOf(success.relativePath).isFile)
        assertEquals("https://cdn.test/4.png", seen.single().imageRef)
        assertEquals(PAGE_VARIANT, seen.single().variant)
        assertEquals("manga_dex", seen.single().sourceId!!.value)
    }

    @Test
    fun `bytes that are not an image are thrown away and reported as corrupt`() = runTest {
        val (downloader, _) = downloader(body = notAnImageBytes())
        val layout = DownloadFileLayout(folder.root)

        val result = downloader.download(target)

        assertEquals(PageDownloadResult.Failed(DownloadError.Corrupt("the downloaded bytes are not an image")), result)
        assertFalse(layout.pageFile("manga_dex", "frieren", "ch-1", 4).exists())
        assertEquals(emptyList<String>(), layout.pageFiles().map { it.name })
    }

    @Test
    fun `a page the source cannot produce is not resolvable`() = runTest {
        val (downloader, _) = downloader(body = null)

        assertEquals(PageDownloadResult.Failed(DownloadError.NotResolvable), downloader.download(target))
        assertFalse(DownloadFileLayout(folder.root).pageFile("manga_dex", "frieren", "ch-1", 4).exists())
    }

    @Test
    fun `a page whose bytes run out is a network error and leaves nothing behind`() = runTest {
        val (downloader, _) = downloader(failure = IOException("unexpected end of stream"))

        assertEquals(PageDownloadResult.Failed(DownloadError.Network), downloader.download(target))
        assertFalse(DownloadFileLayout(folder.root).pageFile("manga_dex", "frieren", "ch-1", 4).exists())
    }

    @Test
    fun `a full disk is reported as a full disk rather than a network error`() = runTest {
        val (downloader, _) = downloader(failure = IOException("write failed: ENOSPC (No space left on device)"))

        assertEquals(PageDownloadResult.Failed(DownloadError.StorageFull), downloader.download(target))
    }

    @Test
    fun `an empty file is not an image`() {
        val file = folder.newFile("empty.bin")

        assertEquals(null, ImageHeaderPageValidator.sizeOf(file))
    }

    @Test
    fun `a page that was validated keeps the size the reader will reserve space for`() {
        val file = folder.newFile("page.bin").apply { writeBytes(pngBytes(1_024, 1_536)) }

        assertEquals(1_024, ImageHeaderPageValidator.sizeOf(file)!!.widthPx)
        assertEquals(1_536, ImageHeaderPageValidator.sizeOf(file)!!.heightPx)
    }
}
