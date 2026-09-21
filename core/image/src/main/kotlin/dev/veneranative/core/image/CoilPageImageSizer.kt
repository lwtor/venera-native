package dev.veneranative.core.image

import dev.veneranative.core.model.ImageSize
import dev.veneranative.core.model.PageImageSizer
import dev.veneranative.core.model.SourceId

/**
 * Resolves a source's image reference to a real pixel size through the image pipeline.
 *
 * Sources return URLs only (`SourcePage.imageRef`) while `ComicPage` needs a size, and this is the
 * seam that closes the gap. It is the only `:core:image` type a data module touches, so a data
 * module never depends on Coil or on OkHttp.
 */
class CoilPageImageSizer(
    private val pipeline: ComicImagePipeline,
    private val requestFor: (imageRef: String, sourceId: SourceId) -> ComicImageRequest =
        { imageRef, sourceId -> ComicImageRequest(url = imageRef, sourceId = sourceId) },
) : PageImageSizer {

    /**
     * Null means "this reference could not be measured", which the caller turns into "skip this
     * page". Nothing is thrown: an unreachable or unrecognised image is a normal source outcome.
     */
    override suspend fun sizeOf(imageRef: String, sourceId: SourceId): ImageSize? =
        pipeline.sizeOf(requestFor(imageRef, sourceId))
}
