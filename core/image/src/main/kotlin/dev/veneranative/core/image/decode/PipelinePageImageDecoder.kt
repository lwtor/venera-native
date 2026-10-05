package dev.veneranative.core.image.decode

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import dev.veneranative.core.image.ComicImagePipeline
import dev.veneranative.core.image.ComicImageRequest
import dev.veneranative.core.image.tiling.DecodedPageImage
import dev.veneranative.core.image.tiling.PageDecodeRequest
import kotlin.math.roundToInt
import java.io.IOException
import kotlinx.coroutines.CancellationException

/** Keeps the authenticated cache file leased throughout decoding. Local fixtures remain local. */
class PipelinePageImageDecoder(
    private val pipeline: ComicImagePipeline,
    private val delegate: PageImageDecoder,
    private val onDecodeFailure: (PageDecodeRequest, Throwable) -> Unit = { _, _ -> },
) : PageImageDecoder by delegate {
    override suspend fun decode(request: PageDecodeRequest): DecodedPageImage {
        if (request.sourceId == null) return delegate.decode(request)
        try {
            val lease = pipeline.cachedFileOf(ComicImageRequest(request.path, request.sourceId, headers = request.headers))
                ?: throw IOException("Page unavailable")
            return lease.use {
                val localRequest = request.copy(path = it.file.absolutePath)
                when {
                    request.reverseHorizontalBands == null -> delegate.decode(localRequest)
                    delegate.strategy == dev.veneranative.core.image.tiling.DecodeStrategy.Sampled ->
                        reverseWholeBitmap(localRequest)
                    else -> reverseRegionBands(localRequest)
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            runCatching { onDecodeFailure(request, failure) }
            throw failure
        }
    }

    override suspend fun predecode(request: PageDecodeRequest) {
        decode(request)
    }

    private suspend fun reverseWholeBitmap(request: PageDecodeRequest): DecodedPageImage {
        val decoded = delegate.decode(request.copy(reverseHorizontalBands = null))
        val height = decoded.bitmap.height
        val bandCount = request.reverseHorizontalBands ?: return decoded
        if (bandCount <= 1 || bandCount > height) return decoded
        val output = Bitmap.createBitmap(decoded.bitmap.width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        val bands = sourceBands(height, bandCount)
        var destinationTop = 0
        for (index in bands.indices.reversed()) {
            val (sourceTop, sourceBottom) = bands[index]
            val bandHeight = sourceBottom - sourceTop
            canvas.drawBitmap(
                decoded.bitmap,
                Rect(0, sourceTop, decoded.bitmap.width, sourceBottom),
                Rect(0, destinationTop, output.width, destinationTop + bandHeight),
                paint,
            )
            destinationTop += bandHeight
        }
        decoded.bitmap.recycle()
        return decoded.copy(bitmap = output, byteCount = output.allocationByteCount)
    }

    /** Reorders only the output tile's intersecting strips, keeping tall pages region-decoded. */
    private suspend fun reverseRegionBands(request: PageDecodeRequest): DecodedPageImage {
        val count = request.reverseHorizontalBands ?: return delegate.decode(request)
        val pageHeight = request.sourceImageHeightPx ?: request.region?.let { it.topPx + it.heightPx }
            ?: return delegate.decode(request.copy(reverseHorizontalBands = null))
        val region = request.region ?: return reverseWholeBitmap(request)
        if (count <= 1 || count > pageHeight || region.heightPx <= 0 || request.targetHeightPx <= 0) {
            return delegate.decode(request.copy(reverseHorizontalBands = null))
        }

        val bands = sourceBands(pageHeight, count)
        val output = Bitmap.createBitmap(request.targetWidthPx, request.targetHeightPx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        val started = android.os.SystemClock.elapsedRealtime()
        var maxSampleSize = 1
        var destinationTop = 0
        try {
            for (sourceIndex in bands.indices.reversed()) {
                val (sourceTop, sourceBottom) = bands[sourceIndex]
                val bandHeight = sourceBottom - sourceTop
                val destinationBottom = destinationTop + bandHeight
                val overlapTop = maxOf(region.topPx, destinationTop)
                val overlapBottom = minOf(region.topPx + region.heightPx, destinationBottom)
                if (overlapBottom > overlapTop) {
                    val sourceSliceTop = sourceTop + overlapTop - destinationTop
                    val sourceSliceBottom = sourceTop + overlapBottom - destinationTop
                    val targetTop = ((overlapTop - region.topPx).toFloat() / region.heightPx * request.targetHeightPx)
                        .roundToInt().coerceIn(0, request.targetHeightPx - 1)
                    val targetBottom = ((overlapBottom - region.topPx).toFloat() / region.heightPx * request.targetHeightPx)
                        .roundToInt().coerceIn(targetTop + 1, request.targetHeightPx)
                    val fragment = delegate.decode(
                        request.copy(
                            region = dev.veneranative.core.image.tiling.PageRegion(
                                leftPx = region.leftPx,
                                topPx = sourceSliceTop,
                                widthPx = region.widthPx,
                                heightPx = sourceSliceBottom - sourceSliceTop,
                            ),
                            targetHeightPx = targetBottom - targetTop,
                            fitWidthOnly = false,
                            reverseHorizontalBands = null,
                        ),
                    )
                    maxSampleSize = maxOf(maxSampleSize, fragment.inSampleSize)
                    try {
                        canvas.drawBitmap(
                            fragment.bitmap,
                            null,
                            Rect(0, targetTop, output.width, targetBottom),
                            paint,
                        )
                    } finally {
                        fragment.bitmap.recycle()
                    }
                }
                destinationTop = destinationBottom
            }
        } catch (failure: Throwable) {
            output.recycle()
            throw failure
        }
        return DecodedPageImage(
            bitmap = output,
            byteCount = output.allocationByteCount,
            decodeMillis = android.os.SystemClock.elapsedRealtime() - started,
            inSampleSize = maxSampleSize,
            strategy = delegate.strategy,
        )
    }

    private fun sourceBands(height: Int, count: Int): List<Pair<Int, Int>> {
        val blockSize = height / count
        var top = 0
        return List(count) { index ->
            val bottom = top + blockSize + if (index == count - 1) height % count else 0
            (top to bottom).also { top = bottom }
        }
    }
}
