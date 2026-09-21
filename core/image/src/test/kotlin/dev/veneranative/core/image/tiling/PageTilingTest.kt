package dev.veneranative.core.image.tiling

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure geometry tests for the S0-06 decoding rules.
 *
 * These run on the JVM because they must hold regardless of device behaviour: they are the part of
 * the large-image strategy that is a rule, not a measurement.
 */
class PageTilingTest {

    private val viewport = PageViewport(widthPx = 1080, heightPx = 2000)

    @Test
    fun widthOnlySamplingKeepsFullResolutionWhenThePageAlreadyFits() {
        assertEquals(
            1,
            PageTiling.calculateInSampleSize(
                sourceWidthPx = 1080,
                sourceHeightPx = 16000,
                targetWidthPx = 1080,
                targetHeightPx = 0,
            ),
        )
    }

    @Test
    fun containSamplingHalvesUntilADimensionWouldFallBelowTheTarget() {
        // 3000x4000 -> 1500x2000 still covers 1080x2000; halving again would give 750x1000.
        assertEquals(
            2,
            PageTiling.calculateInSampleSize(
                sourceWidthPx = 3000,
                sourceHeightPx = 4000,
                targetWidthPx = 1080,
                targetHeightPx = 2000,
            ),
        )
    }

    @Test
    fun samplingNeverReturnsZeroOrNegative() {
        assertEquals(1, PageTiling.calculateInSampleSize(10, 10, 0, 0))
        assertEquals(1, PageTiling.calculateInSampleSize(10, 10, -1, -1))
    }

    @Test
    fun continuousReadingCutsAnUltraLongStripIntoSizedTiles() {
        val tiles = PageTiling.continuousTiles(
            sourceWidthPx = 1080,
            sourceHeightPx = 16000,
            viewport = viewport,
            zoom = 1f,
        )

        // One tile is at most 1.5 screens tall: 2000 * 1.5 / 1.0 = 3000 source pixels.
        assertEquals(6, tiles.size)
        assertEquals(3000, tiles.first().region.heightPx)
        assertEquals(1000, tiles.last().region.heightPx)
        assertEquals(16000, tiles.sumOf { it.region.heightPx })
        tiles.forEach { assertTrue(it.displayWidthPx == 1080) }
    }

    @Test
    fun continuousReadingKeepsANormalPageAsOneTile() {
        val tiles = PageTiling.continuousTiles(
            sourceWidthPx = 1080,
            sourceHeightPx = 1440,
            viewport = viewport,
            zoom = 1f,
        )

        assertEquals(1, tiles.size)
        assertEquals(1440, tiles.single().displayHeightPx)
    }

    @Test
    fun zoomingRaisesTheTileCountBecauseEachTileCoversFewerSourcePixels() {
        val tiles = PageTiling.continuousTiles(
            sourceWidthPx = 1080,
            sourceHeightPx = 16000,
            viewport = viewport,
            zoom = 2f,
        )

        assertTrue(tiles.size > 6)
        assertTrue(tiles.all { it.displayHeightPx <= (viewport.heightPx * PageTiling.TILE_SCREEN_HEIGHT_FACTOR * 2f).toInt() })
    }

    @Test
    fun pagedWindowStartsAtTheTopAndStaysInsideTheSource() {
        val tile = PageTiling.windowTile(
            sourceWidthPx = 1080,
            sourceHeightPx = 16000,
            viewport = viewport,
            zoom = 1f,
            offsetXPx = 0f,
            offsetYPx = 0f,
        )

        assertEquals(0, tile.region.leftPx)
        assertEquals(0, tile.region.topPx)
        assertTrue(tile.region.topPx + tile.region.heightPx <= 16000)
        assertTrue(tile.displayHeightPx <= viewport.heightPx * PageTiling.WINDOW_OVERSCAN_FACTOR + 1)
    }

    @Test
    fun pagedWindowFollowsThePanOffset() {
        val scale = PageTiling.containScale(1080, 16000, viewport)
        val tile = PageTiling.windowTile(
            sourceWidthPx = 1080,
            sourceHeightPx = 16000,
            viewport = viewport,
            zoom = 2f,
            offsetXPx = 0f,
            offsetYPx = -1000f,
        )

        // Panning down moves the decoded window down; the offset is expressed in content pixels.
        assertEquals((1000f / (scale * 2f)).toInt(), tile.region.topPx)
    }

    @Test
    fun containScaleNeverExceedsTheViewport() {
        val scale = PageTiling.containScale(3000, 4000, viewport)

        assertTrue(3000 * scale <= viewport.widthPx + 0.5f)
        assertTrue(4000 * scale <= viewport.heightPx + 0.5f)
    }
}
