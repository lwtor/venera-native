package dev.veneranative.app

import android.content.Context
import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.veneranative.core.image.ComicImageFile
import dev.veneranative.core.image.ComicImagePipeline
import dev.veneranative.core.image.ComicImageRequest
import dev.veneranative.core.image.decode.CachingPageImageDecoder
import dev.veneranative.core.image.decode.PageImageCache
import dev.veneranative.core.image.decode.PipelinePageImageDecoder
import dev.veneranative.core.image.decode.RegionPageImageDecoder
import dev.veneranative.core.image.tiling.PageDecodeRequest
import dev.veneranative.core.image.tiling.PageRegion
import dev.veneranative.core.model.ImageSize
import dev.veneranative.core.model.SourceId
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class StripTransformCacheTest {

    @Test
    fun compositeBitmapRemainsDrawableAfterRepeatedStripDecode() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = File.createTempFile("jm-strip-cache-", ".jpg", context.cacheDir)
        val sourceId = SourceId("jm")
        val pipeline = object : ComicImagePipeline {
            override suspend fun cachedFileOf(request: ComicImageRequest): ComicImageFile =
                ComicImageFile(file, "image/jpeg")

            override suspend fun sizeOf(request: ComicImageRequest): ImageSize? = ImageSize(128, 256)
        }
        val regionDecoder = RegionPageImageDecoder()
        val cache = PageImageCache(2 * 1024 * 1024)
        val decoder = CachingPageImageDecoder(
            PipelinePageImageDecoder(pipeline, regionDecoder),
            cache,
        )
        try {
            Bitmap.createBitmap(128, 256, Bitmap.Config.ARGB_8888).apply {
                eraseColor(android.graphics.Color.BLUE)
                file.outputStream().use { compress(Bitmap.CompressFormat.JPEG, 90, it) }
                recycle()
            }

            val request = PageDecodeRequest(
                path = "https://reader.test/jm-page.jpg",
                sourceId = sourceId,
                targetWidthPx = 128,
                targetHeightPx = 256,
                pageIndex = 0,
                region = PageRegion(0, 0, 128, 256),
                reverseHorizontalBands = 4,
                sourceImageHeightPx = 256,
            )
            val first = decoder.decode(request)
            val second = decoder.decode(request)

            assertSame(first.bitmap, second.bitmap)
            assertFalse("cached composite must not be recycled", second.bitmap.isRecycled)
            assertEquals("cache stores only the completed page", 1, cache.size)
        } finally {
            regionDecoder.close()
            file.delete()
        }
    }
}
