package dev.veneranative.core.image

import dev.veneranative.core.model.ImageSize
import java.io.File

/** Image bytes that are on disk, ready to be decoded or header-parsed. */
class ComicImageFile(
    val file: File,
    val mimeType: String?,
    private val release: () -> Unit = {},
) : java.io.Closeable {
    private val closed = java.util.concurrent.atomic.AtomicBoolean()
    override fun close() { if (closed.compareAndSet(false, true)) release() }
}

/**
 * The comic image pipeline: everything a caller needs from an image before it is drawn.
 *
 * It deliberately exposes files rather than bitmaps. Decoding a comic page is `:core:image`'s own
 * responsibility (ADR-0004), and a caller that only wants the size must not pay for pixels.
 */
interface ComicImagePipeline {

    /** Ensures the bytes are on disk and returns the cached file; null when they cannot be fetched. */
    suspend fun cachedFileOf(request: ComicImageRequest): ComicImageFile?

    /** The real size of the image, without decoding it; null means "could not be resolved". */
    suspend fun sizeOf(request: ComicImageRequest): ImageSize?
}
