package dev.veneranative.core.image

import android.content.Context
import coil3.ImageLoader
import coil3.disk.DiskCache
import java.io.File
import okio.Path.Companion.toOkioPath

/**
 * Builds the app's comic [ImageLoader].
 *
 * Coil is configured with our own keyer and fetcher and **without** its network artifacts: the
 * network is `:core:network`'s shared OkHttp client, so connection pool, dispatcher and timeouts are
 * the same for images as for every other request in the app.
 */
fun comicImageLoader(
    context: Context,
    auth: ComicImageAuthProvider,
    pipeline: ComicImagePipeline,
    diskCache: DiskCache = comicImageDiskCache(context),
): ImageLoader = ImageLoader.Builder(context)
    .components {
        add(ComicImageKeyer(auth))
        add(ComicImageFetcher.Factory(pipeline))
    }
    // Authentication may change between a Coil key lookup and fetch. Keep bytes in the
    // authenticated disk pipeline until immutable request identities support memory caching.
    .memoryCache(null)
    .diskCache(diskCache)
    .build()

/** The image disk cache, kept in the app's cache directory so the OS can reclaim it. */
fun comicImageDiskCache(
    context: Context,
    maxSizeBytes: Long = DEFAULT_DISK_CACHE_BYTES,
): DiskCache = DiskCache.Builder()
    .directory(File(context.cacheDir, DISK_CACHE_DIRECTORY).toOkioPath())
    .maxSizeBytes(maxSizeBytes)
    .build()

private const val DISK_CACHE_DIRECTORY = "comic_images"
private const val DEFAULT_DISK_CACHE_BYTES = 250L * 1024 * 1024
