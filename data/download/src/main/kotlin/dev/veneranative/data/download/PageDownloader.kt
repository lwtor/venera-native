package dev.veneranative.data.download

import dev.veneranative.core.image.ComicImagePipeline
import dev.veneranative.core.image.ComicImageRequest
import dev.veneranative.core.image.ImageSizeHeaderParser
import dev.veneranative.core.model.ImageSize
import dev.veneranative.core.model.SourceId
import java.io.File
import java.io.IOException
import java.io.OutputStream

/** One page's bytes, as the downloader asks for them. */
data class PageFetchRequest(
    val imageRef: String,
    val sourceId: SourceId?,
    val variant: String = PAGE_VARIANT,
)

/** Where a page's bytes come from. The downloader does not care whether that is a network or a test. */
interface PageByteSource {

    /** Streams the bytes into [sink] and reports how many; null when they could not be produced. */
    suspend fun fetch(request: PageFetchRequest, sink: OutputStream): Long?
}

/**
 * Pages come from the image pipeline, the same one the reader uses.
 *
 * That is the point: a page the reader can display because it has the source's cookies and referer
 * must be downloadable for exactly the same reason. A downloader with its own HTTP client would
 * quietly produce "can read but cannot save" on every source that protects its images.
 */
class ComicImagePipelinePageSource(
    private val pipeline: ComicImagePipeline,
) : PageByteSource {

    override suspend fun fetch(request: PageFetchRequest, sink: OutputStream): Long? {
        val image = pipeline.cachedFileOf(
            ComicImageRequest(
                url = request.imageRef,
                sourceId = request.sourceId,
                variant = request.variant,
            ),
        ) ?: return null
        return image.use { lease -> lease.file.inputStream().use { input -> input.copyTo(sink) } }
    }
}

/** Decides whether a file that landed is really an image. */
fun interface PageValidator {

    /** The image's size, or null when the bytes are not a readable image. */
    fun sizeOf(file: File): ImageSize?
}

/**
 * Validates by parsing the header, reusing the reader's own parser.
 *
 * This is not paranoia: a hotlink guard answers with an HTML error page and a 200, so without this
 * check the queue would fill the device with files that decode to nothing and the reader would open
 * a blank page with no explanation. A page that is not an image is a failed page — failing is more
 * useful to the user than storing something that only looks like a page.
 */
object ImageHeaderPageValidator : PageValidator {

    private const val HEADER_BYTES: Int = 64 * 1024

    override fun sizeOf(file: File): ImageSize? {
        if (!file.isFile) return null
        val header = file.inputStream().use { input ->
            val size = minOf(HEADER_BYTES, file.length().toInt())
            if (size <= 0) return null
            val buffer = ByteArray(size)
            val read = input.read(buffer)
            if (read <= 0) return null
            if (read == buffer.size) buffer else buffer.copyOf(read)
        }
        return ImageSizeHeaderParser.parse(header)
    }
}

/** The page a download run is about. */
data class DownloadTarget(
    val sourceId: String,
    val comicId: String,
    val chapterId: String,
    val index: Int,
    val imageRef: String,
)

/** How one page run ended. */
sealed interface PageDownloadResult {

    data class Succeeded(
        val relativePath: String,
        val bytes: Long,
        val widthPx: Int,
        val heightPx: Int,
    ) : PageDownloadResult

    data class Failed(val error: DownloadError) : PageDownloadResult
}

/**
 * Fetches one page, writes it atomically and checks that what landed is an image.
 *
 * The order is deliberate: validate after the rename, and delete the file when validation fails. A
 * file that failed the check must not stay behind, or the next scan would see a complete-looking
 * page and the reader would open a blank one.
 */
class PageDownloader(
    private val layout: DownloadFileLayout,
    private val source: PageByteSource,
    private val validator: PageValidator = ImageHeaderPageValidator,
) {

    suspend fun download(target: DownloadTarget): PageDownloadResult {
        val file = layout.pageFile(target.sourceId, target.comicId, target.chapterId, target.index)
        val request = PageFetchRequest(imageRef = target.imageRef, sourceId = SourceId(target.sourceId))

        val written = try {
            layout.writeAtomically(file) { sink ->
                source.fetch(request, sink) ?: throw PageBytesMissing()
            }
        } catch (missing: PageBytesMissing) {
            return PageDownloadResult.Failed(DownloadError.NotResolvable)
        } catch (failure: IOException) {
            discard(file)
            return PageDownloadResult.Failed(failure.asDownloadError())
        }

        val size = validator.sizeOf(file)
        if (size == null) {
            discard(file)
            return PageDownloadResult.Failed(DownloadError.Corrupt(NOT_AN_IMAGE))
        }

        return PageDownloadResult.Succeeded(
            relativePath = layout.relativePathOf(
                sourceId = target.sourceId,
                comicId = target.comicId,
                chapterId = target.chapterId,
                index = target.index,
            ),
            bytes = written,
            widthPx = size.widthPx,
            heightPx = size.heightPx,
        )
    }

    private fun discard(file: File) {
        if (file.exists() && !file.delete()) Unit
    }

    private fun IOException.asDownloadError(): DownloadError =
        if (message?.contains("ENOSPC", ignoreCase = true) == true) {
            DownloadError.StorageFull
        } else {
            DownloadError.Network
        }

    private class PageBytesMissing : IOException("the page bytes could not be produced")
}

/** Keeps a downloaded page's cache entry apart from the same URL read as a cover or a page on screen. */
const val PAGE_VARIANT: String = "download-page"

private const val NOT_AN_IMAGE: String = "the downloaded bytes are not an image"
