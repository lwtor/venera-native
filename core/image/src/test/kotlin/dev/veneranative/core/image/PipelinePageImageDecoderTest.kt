package dev.veneranative.core.image

import dev.veneranative.core.image.decode.*
import dev.veneranative.core.image.tiling.*
import dev.veneranative.core.model.*
import java.io.IOException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PipelinePageImageDecoderTest {
    @get:Rule val temp = TemporaryFolder()
    @Test fun remoteReferenceBecomesLeasedFileAndClosesOnDecoderFailure() = runTest {
        val file = temp.newFile().apply { writeText("pixels") }
        var closed = false
        val pipeline = object : ComicImagePipeline {
            override suspend fun cachedFileOf(request: ComicImageRequest): ComicImageFile {
                assertEquals(SourceId("source"), request.sourceId)
                assertEquals("https://image/page", request.url)
                return ComicImageFile(file, null) { closed = true }
            }
            override suspend fun sizeOf(request: ComicImageRequest): ImageSize? = null
        }
        val delegate = object : PageImageDecoder {
            override val strategy = DecodeStrategy.Region
            override fun plan(page: ComicPage, viewport: PageViewport, zoom: Float, continuous: Boolean): List<PageTile> = emptyList()
            override suspend fun decode(request: PageDecodeRequest): DecodedPageImage {
                assertEquals(file.absolutePath, request.path)
                assertFalse(closed)
                assertEquals("pixels", java.io.File(request.path).readText())
                throw IOException("decode failure")
            }
        }
        val failure = runCatching {
            PipelinePageImageDecoder(pipeline, delegate).decode(PageDecodeRequest("https://image/page", SourceId("source"), 10, 10))
        }.exceptionOrNull()
        assertEquals("decode failure", failure?.message)
        assertTrue(closed)
    }
}
