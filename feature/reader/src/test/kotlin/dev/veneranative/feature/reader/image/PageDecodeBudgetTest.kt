package dev.veneranative.feature.reader.image

import dev.veneranative.core.model.ComicPage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Device-free evidence for ADR-0004.
 *
 * Decode memory is arithmetic before it is a measurement: a decoded bitmap occupies
 * `width * height * bytesPerPixel`. Whether a plan fits in memory is therefore decided by the plan,
 * not by the device, so the invariants below are enforced here instead of being observed on a
 * phone. They exist so that a later change to tiling fails the build rather than a device.
 *
 * The instrumentation probe (`LargeImageProbeTest`) still covers what arithmetic cannot answer —
 * decode time, PSS and real scroll behaviour — and runs at Stage 0 exit, not on every change.
 */
class PageDecodeBudgetTest {

    private val viewport = PageViewport(widthPx = 1080, heightPx = 2000)

    private val budgetBytes = PageTiling.DEFAULT_DECODE_BUDGET_BYTES

    private data class Source(val name: String, val widthPx: Int, val heightPx: Int) {
        fun toPage(): ComicPage =
            ComicPage(index = 0, imageRef = "fixture://$name", widthPx = widthPx, heightPx = heightPx)
    }

    private val commonPage = Source("common page 1080x1440", 1080, 1440)

    private val sources = listOf(
        commonPage,
        Source("wide page 1920x1080", 1920, 1080),
        Source("long strip 1080x6000", 1080, 6000),
        Source("ultra long strip 1080x16000", 1080, 16000),
        Source("high resolution 3000x4000", 3000, 4000),
    )

    @Test
    fun `region decoding never exceeds the decode budget`() {
        val decoder = RegionPageImageDecoder()

        sources.forEach { source ->
            listOf(1f, 2f, 3f, 5f).forEach { zoom ->
                listOf(true, false).forEach { continuous ->
                    decoder.plan(source.toPage(), viewport, zoom, continuous).forEach { tile ->
                        val bytes = PageTiling.regionDecodeByteCount(tile)
                        assertTrue(
                            "${source.name} zoom=$zoom continuous=$continuous allocated $bytes bytes",
                            bytes <= budgetBytes,
                        )
                    }
                }
            }
        }
    }

    @Test
    fun `region decoding cost does not grow with page length`() {
        val heights = listOf(4000, 16000, 64000)

        val peaks = heights.map { heightPx ->
            PageTiling.continuousTiles(1080, heightPx, viewport, zoom = 1f)
                .maxOf { PageTiling.regionDecodeByteCount(it) }
        }

        peaks.forEach { bytes -> assertTrue("a tile allocated $bytes bytes", bytes <= budgetBytes) }
        // A page four times longer than the ultra long strip must not cost more per decode.
        assertEquals(peaks[1], peaks[2])
    }

    @Test
    fun `tiling covers the page without gaps or overlaps`() {
        sources.forEach { source ->
            val tiles = PageTiling.continuousTiles(source.widthPx, source.heightPx, viewport, zoom = 1f)
            var nextTopPx = 0
            tiles.forEach { tile ->
                assertEquals("${source.name}: unexpected tile start", nextTopPx, tile.region.topPx)
                nextTopPx += tile.region.heightPx
            }
            assertEquals("${source.name}: page is not fully covered", source.heightPx, nextTopPx)
        }
    }

    @Test
    fun `pages that fit the budget are decoded as one piece`() {
        val decoder = RegionPageImageDecoder()

        listOf(commonPage, Source("high resolution 3000x4000", 3000, 4000)).forEach { source ->
            val tiles = decoder.plan(source.toPage(), viewport, zoom = 1f, continuous = true)
            assertEquals("${source.name} should not be tiled", 1, tiles.size)
            assertEquals(source.heightPx, tiles.single().region.heightPx)
        }
    }

    @Test
    fun `continuous reading tiles ultra long strips instead of decoding them whole`() {
        val source = Source("ultra long strip 1080x16000", 1080, 16000)

        val tiles = RegionPageImageDecoder().plan(source.toPage(), viewport, zoom = 1f, continuous = true)

        assertTrue("a strip must be cut into tiles, got ${tiles.size}", tiles.size > 1)
        assertEquals(source.heightPx, tiles.sumOf { it.region.heightPx })
    }

    @Test
    fun `whole page sampling is affordable for a common page`() {
        val tiles = SampledPageImageDecoder().plan(commonPage.toPage(), viewport, zoom = 1f, continuous = true)

        assertTrue(PageTiling.sampledDecodeByteCount(tiles.single()) <= budgetBytes)
    }

    @Test
    fun `whole page sampling breaks the budget on an ultra long strip`() {
        val source = Source("ultra long strip 1080x16000", 1080, 16000)

        val tiles = SampledPageImageDecoder().plan(source.toPage(), viewport, zoom = 1f, continuous = true)
        val bytes = PageTiling.sampledDecodeByteCount(tiles.single())

        assertTrue("sampled decoding would allocate $bytes bytes", bytes > budgetBytes)
    }

    @Test
    fun `whole page sampling cost is proportional to page size`() {
        val half = SampledPageImageDecoder().plan(
            Source("half strip", 1080, 8000).toPage(),
            viewport,
            zoom = 1f,
            continuous = true,
        )
        val full = SampledPageImageDecoder().plan(
            Source("full strip", 1080, 16000).toPage(),
            viewport,
            zoom = 1f,
            continuous = true,
        )

        assertEquals(
            PageTiling.sampledDecodeByteCount(full.single()),
            PageTiling.sampledDecodeByteCount(half.single()) * 2,
        )
    }

    @Test
    fun `the budget stays below three viewports of pixels`() {
        val viewportBytes = PageTiling.decodedByteCount(viewport.widthPx, viewport.heightPx)

        assertTrue(budgetBytes <= viewportBytes * 3)
    }
}
