package dev.veneranative.app

import android.app.Application
import coil3.ImageLoader
import coil3.disk.DiskCache
import dev.veneranative.core.database.VeneraDatabaseFactory
import dev.veneranative.core.image.CoilComicImagePipeline
import dev.veneranative.core.image.ComicImagePipeline
import dev.veneranative.core.image.comicImageDiskCache
import dev.veneranative.core.image.comicImageLoader
import dev.veneranative.core.network.AppHttpClientFactory
import dev.veneranative.data.download.ComicImagePipelinePageSource
import dev.veneranative.data.download.DownloadEnvironment
import dev.veneranative.data.download.DownloadLimits
import dev.veneranative.source.network.PerSourceCookieJarRegistry
import java.io.File
import kotlinx.coroutines.Dispatchers
import okhttp3.OkHttpClient

/**
 * What the process owns, rather than what a screen owns.
 *
 * Everything here exists because the download worker needs it and the worker may run in a process the
 * system started without a single Activity: the Application's `onCreate` is the only place that is
 * guaranteed to have run before it. The HTTP client and the cookie registry in particular must be the
 * **same instances** the screens use — a source authenticates while loading its chapter list, and
 * those cookies are exactly what its image host later demands. A second registry would look correct
 * and 403 every page.
 */
class VeneraApplication : Application() {

    val cookieJars: PerSourceCookieJarRegistry = PerSourceCookieJarRegistry()

    val httpClient: OkHttpClient = AppHttpClientFactory.create(AppHttpClientFactory.createDispatcher())

    val authProvider: SourceCookieImageAuth = SourceCookieImageAuth(cookieJars)

    val diskCache: DiskCache by lazy { comicImageDiskCache(this) }

    val imagePipeline: ComicImagePipeline by lazy {
        CoilComicImagePipeline(httpClient, diskCache, authProvider)
    }

    val imageLoader: ImageLoader by lazy {
        comicImageLoader(this, authProvider, imagePipeline, diskCache)
    }

    override fun onCreate() {
        super.onCreate()
        DownloadEnvironment.install(
            DownloadEnvironment(
                filesRoot = File(filesDir, DOWNLOAD_DIRECTORY),
                clock = { System.currentTimeMillis() },
                io = Dispatchers.IO,
                limits = DownloadLimits(),
                pageSource = ComicImagePipelinePageSource(imagePipeline),
                database = { VeneraDatabaseFactory.get(this) },
            ),
        )
    }

    private companion object {

        const val DOWNLOAD_DIRECTORY: String = "downloads"
    }
}
