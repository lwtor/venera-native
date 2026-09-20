package dev.veneranative.feature.reader.image

import dev.veneranative.core.model.ComicPage
import java.io.Closeable

/**
 * Turns page descriptors into pixels.
 *
 * The reader only knows this contract: it asks for a tiling plan and then decodes the tiles it is
 * about to show. Nothing here may put a `Bitmap` into `ReaderUiState`; decoded pixels live in the
 * cache owned by the caller and in composition-scoped references.
 */
interface PageImageDecoder : Closeable {

    val strategy: DecodeStrategy

    /**
     * Splits [page] into decode units for the current viewport and zoom.
     *
     * [continuous] is true for vertical strip reading and false for one page per screen.
     */
    fun plan(
        page: ComicPage,
        viewport: PageViewport,
        zoom: Float,
        continuous: Boolean,
    ): List<PageTile>

    /**
     * Re-plans the single tile shown by paged reading for the current pan offset.
     *
     * Decoders that always show the whole page keep the default; region decoders narrow it.
     */
    fun planWindow(
        page: ComicPage,
        viewport: PageViewport,
        zoom: Float,
        offsetXPx: Float,
        offsetYPx: Float,
    ): PageTile = plan(page = page, viewport = viewport, zoom = zoom, continuous = false).first()

    suspend fun decode(request: PageDecodeRequest): DecodedPageImage

    override fun close() = Unit
}
