package dev.veneranative.core.image

import coil3.ImageLoader
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.fetch.Fetcher
import coil3.fetch.FetchResult
import coil3.fetch.SourceFetchResult
import coil3.request.Options
import okio.Path.Companion.toOkioPath

/**
 * Hands Coil the bytes of a [ComicImageRequest].
 *
 * The image pipeline, not this fetcher, owns downloading and disk caching: Coil 3 leaves disk
 * caching to the fetcher, and sharing one implementation means a page whose size was resolved and a
 * page that is rendered reuse the very same cache entry instead of downloading twice.
 */
class ComicImageFetcher(
    private val request: ComicImageRequest,
    private val options: Options,
    private val pipeline: ComicImagePipeline,
) : Fetcher {

    override suspend fun fetch(): FetchResult? {
        val file = pipeline.cachedFileOf(request) ?: return null
        return SourceFetchResult(
            source = ImageSource(file = file.file.toOkioPath(), fileSystem = options.fileSystem),
            mimeType = file.mimeType,
            dataSource = DataSource.NETWORK,
        )
    }

    class Factory(
        private val pipeline: ComicImagePipeline,
    ) : Fetcher.Factory<ComicImageRequest> {

        override fun create(
            data: ComicImageRequest,
            options: Options,
            imageLoader: ImageLoader,
        ): Fetcher = ComicImageFetcher(request = data, options = options, pipeline = pipeline)
    }
}
