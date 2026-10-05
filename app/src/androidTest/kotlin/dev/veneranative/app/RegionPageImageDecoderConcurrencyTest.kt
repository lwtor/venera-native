package dev.veneranative.app

import android.content.Context
import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.veneranative.core.image.decode.RegionPageImageDecoder
import dev.veneranative.core.image.tiling.PageDecodeRequest
import dev.veneranative.core.image.tiling.PageRegion
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RegionPageImageDecoderConcurrencyTest {

    @Test
    fun activeImageDecodesSurviveLruEvictionWhileScrollingAcrossPages() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val files = (0 until 8).map { index ->
            File.createTempFile("reader-region-$index-", ".jpg", context.cacheDir).also { file ->
                Bitmap.createBitmap(512, 1024, Bitmap.Config.ARGB_8888).apply {
                    eraseColor(android.graphics.Color.rgb(index * 20, 80, 140))
                    file.outputStream().use { compress(Bitmap.CompressFormat.JPEG, 92, it) }
                    recycle()
                }
            }
        }
        val decoder = RegionPageImageDecoder(maxOpenDecoders = 1)
        val completed = AtomicInteger()
        try {
            val results = (0 until 64).map { index ->
                async(Dispatchers.IO) {
                    val image = files[index % files.size]
                    decoder.decode(
                        PageDecodeRequest(
                            path = image.absolutePath,
                            targetWidthPx = 256,
                            targetHeightPx = 256,
                            region = PageRegion(leftPx = 0, topPx = (index % 3) * 256, widthPx = 512, heightPx = 256),
                        ),
                    ).also { completed.incrementAndGet() }
                }
            }.awaitAll()

            assertEquals(64, completed.get())
            results.forEach { result ->
                assertEquals(512, result.bitmap.width)
                assertEquals(256, result.bitmap.height)
                result.bitmap.recycle()
            }
        } finally {
            decoder.close()
            files.forEach(File::delete)
        }
    }
}
