package dev.veneranative.core.image.decode

import android.graphics.BitmapFactory
import android.os.SystemClock
import dev.veneranative.core.image.tiling.DecodeStrategy
import dev.veneranative.core.image.tiling.DecodedPageImage
import dev.veneranative.core.image.tiling.PageDecodeRequest
import dev.veneranative.core.image.tiling.PageTile
import dev.veneranative.core.image.tiling.PageTiling
import dev.veneranative.core.image.tiling.PageViewport
import dev.veneranative.core.model.ComicPage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Baseline decoder: decode the whole page, down-sampled to the requested size.
 *
 * This mirrors what a generic image loader does when it fits an image to a target size, and it is
 * the behaviour S0-06 measures against. It is expected to fail on ultra long strips: sampling by
 * width only still produces a bitmap as tall as the whole strip.
 */
class SampledPageImageDecoder(
    private val clock: () -> Long = { SystemClock.elapsedRealtime() },
) : PageImageDecoder {

    override val strategy: DecodeStrategy = DecodeStrategy.Sampled

    override fun plan(
        page: ComicPage,
        viewport: PageViewport,
        zoom: Float,
        continuous: Boolean,
    ): List<PageTile> = listOf(
        PageTiling.wholePageTile(
            pageWidthPx = page.widthPx,
            pageHeightPx = page.heightPx,
            viewport = viewport,
            zoom = zoom,
            continuous = continuous,
        ),
    )

    override suspend fun decode(request: PageDecodeRequest): DecodedPageImage = withContext(Dispatchers.IO) {
        val startedAtMillis = clock()
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(request.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            throw IllegalStateException("Page image is not decodable")
        }
        val targetHeightPx = if (request.fitWidthOnly) 0 else request.targetHeightPx
        val inSampleSize = PageTiling.calculateInSampleSize(
            sourceWidthPx = bounds.outWidth,
            sourceHeightPx = bounds.outHeight,
            targetWidthPx = request.targetWidthPx,
            targetHeightPx = targetHeightPx,
        )
        val bitmap = BitmapFactory.decodeFile(
            request.path,
            BitmapFactory.Options().apply { this.inSampleSize = inSampleSize },
        ) ?: throw IllegalStateException("Page image is not decodable")
        DecodedPageImage(
            bitmap = bitmap,
            byteCount = bitmap.allocationByteCount,
            decodeMillis = clock() - startedAtMillis,
            inSampleSize = inSampleSize,
            strategy = strategy,
        )
    }
}
