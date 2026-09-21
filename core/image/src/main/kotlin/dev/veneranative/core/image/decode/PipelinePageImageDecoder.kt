package dev.veneranative.core.image.decode

import dev.veneranative.core.image.ComicImagePipeline
import dev.veneranative.core.image.ComicImageRequest
import dev.veneranative.core.image.tiling.DecodedPageImage
import dev.veneranative.core.image.tiling.PageDecodeRequest
import java.io.IOException

/** Keeps the authenticated cache file leased throughout decoding. Local fixtures remain local. */
class PipelinePageImageDecoder(
    private val pipeline: ComicImagePipeline,
    private val delegate: PageImageDecoder,
) : PageImageDecoder by delegate {
    override suspend fun decode(request: PageDecodeRequest): DecodedPageImage {
        if (request.sourceId == null) return delegate.decode(request)
        val lease = pipeline.cachedFileOf(ComicImageRequest(request.path, request.sourceId))
            ?: throw IOException("Page unavailable")
        return lease.use { delegate.decode(request.copy(path = it.file.absolutePath)) }
    }
}
