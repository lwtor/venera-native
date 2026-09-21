package dev.veneranative.feature.reader.image

import android.app.ActivityManager
import android.content.Context
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Process
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.veneranative.core.image.decode.CachingPageImageDecoder
import dev.veneranative.core.image.decode.PageImageCache
import dev.veneranative.core.image.decode.PageImageDecoder
import dev.veneranative.core.image.decode.RegionPageImageDecoder
import dev.veneranative.core.image.decode.SampledPageImageDecoder
import dev.veneranative.core.image.tiling.DecodeStrategy
import dev.veneranative.core.image.tiling.PageDecodeRequest
import dev.veneranative.core.image.tiling.PageTile
import dev.veneranative.core.image.tiling.PageViewport
import dev.veneranative.core.model.ComicPage
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith



/**
 * S0-06 measurement harness: produces the numbers that decide the large-image strategy.
 *
 * It is instrumentation code because only a real device reports real decode cost and real PSS.
 * Fixtures must be generated first (see `tools/test-images`); the tests skip when they are absent
 * so a clean clone still compiles and passes.
 *
 * Collect the data with:
 *
 * ```powershell
 * adb logcat -c
 * adb shell am instrument -w dev.veneranative.feature.reader.test/androidx.test.runner.AndroidJUnitRunner
 * adb logcat -d -s VeneraImage:I
 * ```
 */
@RunWith(AndroidJUnit4::class)
class LargeImageProbeTest {

    @Test
    fun measuresDecodeCostPerStrategyAndMode() {
        val context: Context = ApplicationProvider.getApplicationContext()
        val fixtures = prepareFixtures(context)
        assumeTrue(
            "Generate the S0-06 fixtures first: java -Xmx2g tools/test-images/GenerateTestImages.java " +
                "feature/reader/src/main/assets/fixtures",
            fixtures.isNotEmpty(),
        )
        val viewport = viewport(context)
        Log.i(TAG, "device=${Build.MODEL} sdk=${Build.VERSION.SDK_INT} viewport=${viewport.widthPx}x${viewport.heightPx}")

        for (fixture in fixtures) {
            for (strategy in DecodeStrategy.entries) {
                measureContinuous(context, fixture, strategy, viewport)
                measurePaged(context, fixture, strategy, viewport)
            }
        }
    }

    @Test
    fun measuresPrefetchWindowPeakMemory() {
        val context: Context = ApplicationProvider.getApplicationContext()
        val fixtures = prepareFixtures(context)
        assumeTrue("Generate the S0-06 fixtures first", fixtures.isNotEmpty())
        val viewport = viewport(context)

        for (radius in intArrayOf(1, 2, 3)) {
            for (strategy in DecodeStrategy.entries) {
                measurePrefetchWindow(context, fixtures, strategy, viewport, radius)
            }
        }
    }

    private fun measureContinuous(
        context: Context,
        fixture: Fixture,
        strategy: DecodeStrategy,
        viewport: PageViewport,
    ) {
        val cache = PageImageCache(DEFAULT_CACHE_BYTES)
        val decoder = CachingPageImageDecoder(decoderFor(strategy), cache)
        try {
            val page = fixture.toPage()
            val tiles = decoder.plan(page, viewport, 1f, continuous = true)
            val pssBeforeKb = pssKb(context)
            var decodeMillis = 0L
            runBlocking {
                for (tile in tiles) {
                    val decoded = decoder.decode(request(fixture.path, tile))
                    decodeMillis += decoded.decodeMillis
                }
            }
            val pssAfterKb = pssKb(context)
            log(
                "fixture=${fixture.name} strategy=$strategy mode=continuous tiles=${tiles.size} " +
                    "decodeMs=$decodeMillis cachedBytes=${cache.sizeBytes} cachedTiles=${cache.size} " +
                    "pssDeltaKb=${pssAfterKb - pssBeforeKb}"
            )
        } finally {
            cache.clear()
            decoder.close()
        }
    }

    private fun measurePaged(
        context: Context,
        fixture: Fixture,
        strategy: DecodeStrategy,
        viewport: PageViewport,
    ) {
        val cache = PageImageCache(DEFAULT_CACHE_BYTES)
        val decoder = CachingPageImageDecoder(decoderFor(strategy), cache)
        try {
            val page = fixture.toPage()
            for (zoom in floatArrayOf(1f, 3f)) {
                val tile = decoder.planWindow(page, viewport, zoom, 0f, 0f)
                val pssBeforeKb = pssKb(context)
                val decoded = runBlocking { decoder.decode(request(fixture.path, tile)) }
                val pssAfterKb = pssKb(context)
                log(
                    "fixture=${fixture.name} strategy=$strategy mode=paged zoom=$zoom " +
                        "inSampleSize=${decoded.inSampleSize} bitmap=${decoded.widthPx}x${decoded.heightPx} " +
                        "decodeMs=${decoded.decodeMillis} bitmapBytes=${decoded.byteCount} " +
                        "pssDeltaKb=${pssAfterKb - pssBeforeKb}"
                )
            }
        } finally {
            cache.clear()
            decoder.close()
        }
    }

    /** Holds a window of pages alive at once, which is what a prefetch radius actually costs. */
    private fun measurePrefetchWindow(
        context: Context,
        fixtures: List<Fixture>,
        strategy: DecodeStrategy,
        viewport: PageViewport,
        radius: Int,
    ) {
        val cache = PageImageCache(DEFAULT_CACHE_BYTES)
        val decoder = CachingPageImageDecoder(decoderFor(strategy), cache)
        try {
            val pssBeforeKb = pssKb(context)
            var decodeMillis = 0L
            var peakCachedBytes = 0L
            val window = 2 * radius + 1
            val pages = fixtures.take(window).map { it.toPage() }
            runBlocking {
                for (page in pages) {
                    for (tile in decoder.plan(page, viewport, 1f, continuous = true)) {
                        decodeMillis += decoder.decode(request(page.imageRef, tile)).decodeMillis
                    }
                    peakCachedBytes = maxOf(peakCachedBytes, cache.sizeBytes)
                }
            }
            val pssAfterKb = pssKb(context)
            log(
                "strategy=$strategy prefetchRadius=$radius pages=${pages.size} decodeMs=$decodeMillis " +
                    "peakCachedBytes=$peakCachedBytes cachedTiles=${cache.size} " +
                    "pssDeltaKb=${pssAfterKb - pssBeforeKb}"
            )
        } finally {
            cache.clear()
            decoder.close()
        }
    }

    private fun request(path: String, tile: PageTile): PageDecodeRequest = PageDecodeRequest(
        path = path,
        targetWidthPx = tile.displayWidthPx,
        targetHeightPx = tile.displayHeightPx,
        region = tile.region,
        fitWidthOnly = tile.fitWidthOnly,
    )

    private fun decoderFor(strategy: DecodeStrategy): PageImageDecoder = when (strategy) {
        DecodeStrategy.Sampled -> SampledPageImageDecoder()
        DecodeStrategy.Region -> RegionPageImageDecoder()
    }

    private fun viewport(context: Context): PageViewport {
        val metrics = context.resources.displayMetrics
        return PageViewport(widthPx = metrics.widthPixels, heightPx = metrics.heightPixels)
    }

    private fun pssKb(context: Context): Long {
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val info = manager.getProcessMemoryInfo(intArrayOf(Process.myPid()))
        return info.firstOrNull()?.totalPss?.toLong() ?: 0L
    }

    private fun prepareFixtures(context: Context): List<Fixture> {
        val names = (context.assets.list(ASSET_DIR) ?: emptyArray())
            .filter { name ->
                name.endsWith(".png", ignoreCase = true) || name.endsWith(".jpg", ignoreCase = true)
            }
            .sorted()
        val targetDir = File(context.cacheDir, ASSET_DIR).apply { mkdirs() }
        return names.mapNotNull { name ->
            val file = File(targetDir, name)
            if (!file.exists()) {
                context.assets.open("$ASSET_DIR/$name").use { input ->
                    file.outputStream().use { output -> input.copyTo(output) }
                }
            }
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                null
            } else {
                Fixture(name = name, path = file.absolutePath, widthPx = bounds.outWidth, heightPx = bounds.outHeight)
            }
        }
    }

    private fun log(message: String) {
        Log.i(TAG, message)
    }

    private data class Fixture(val name: String, val path: String, val widthPx: Int, val heightPx: Int) {
        fun toPage(): ComicPage = ComicPage(index = 0, imageRef = path, widthPx = widthPx, heightPx = heightPx)
    }

    private companion object {
        const val TAG = "VeneraImage"
        const val ASSET_DIR = "fixtures"
        const val DEFAULT_CACHE_BYTES = 64L * 1024 * 1024
    }
}
