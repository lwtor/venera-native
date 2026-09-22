package dev.veneranative.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.veneranative.core.database.VeneraDatabase
import dev.veneranative.core.database.VeneraDatabaseFactory
import dev.veneranative.core.designsystem.VeneraNativeTheme
import dev.veneranative.core.image.CoilComicImagePipeline
import dev.veneranative.core.image.CoilPageImageSizer
import dev.veneranative.core.image.ComicImageAuthProvider
import dev.veneranative.core.image.comicImageDiskCache
import dev.veneranative.core.image.comicImageLoader
import dev.veneranative.core.image.compose.LocalComicImageLoader
import dev.veneranative.core.image.decode.CachingPageImageDecoder
import dev.veneranative.core.image.decode.PageImageCache
import dev.veneranative.core.image.decode.PageImageDecoder
import dev.veneranative.core.image.decode.RegionPageImageDecoder
import dev.veneranative.core.image.decode.SampledPageImageDecoder
import dev.veneranative.core.image.tiling.DecodeStrategy
import dev.veneranative.core.model.ChapterKey
import dev.veneranative.core.model.ComicKey
import dev.veneranative.core.model.PageProvider
import dev.veneranative.core.model.RemoteChapterId
import dev.veneranative.core.model.RemoteComicId
import dev.veneranative.core.model.SourceId
import dev.veneranative.core.navigation.AppRoute
import dev.veneranative.core.navigation.decodeAppRoute
import dev.veneranative.core.navigation.encode
import dev.veneranative.core.network.AppHttpClientFactory
import dev.veneranative.data.comic.DefaultComicCatalog
import dev.veneranative.data.comic.SourcePageProvider
import dev.veneranative.data.collection.ComicCatalogChapterProbe
import dev.veneranative.data.collection.CollectionRepository
import dev.veneranative.data.collection.DefaultCollectionRepository
import dev.veneranative.data.history.DefaultHistoryRepository
import dev.veneranative.data.history.HistoryRepository
import dev.veneranative.data.history.ReadingHistoryEntry
import dev.veneranative.data.history.ReadingProgressTracker
import dev.veneranative.data.source.DefaultSourceRepository
import dev.veneranative.data.source.AndroidSourceScriptFetcher
import dev.veneranative.data.source.SourcePackageStore
import dev.veneranative.feature.details.DetailsRoute
import dev.veneranative.feature.explore.ExploreRoute
import dev.veneranative.feature.home.HomeRoute
import dev.veneranative.feature.reader.ReaderRoute
import dev.veneranative.feature.reader.ReaderViewModel
import dev.veneranative.feature.search.SearchRoute
import dev.veneranative.feature.sources.SourcesRoute
import dev.veneranative.source.core.EngineSourceCore
import dev.veneranative.source.engine.QuickJsMetadataReader
import dev.veneranative.source.engine.QuickJsRuntime
import dev.veneranative.source.network.PerSourceCookieJarRegistry
import dev.veneranative.source.network.SourceNetworkExecutor
import dev.veneranative.source.network.SourceNetworkHostApi
import java.io.File
import java.util.Locale
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class AppGraph(application: android.app.Application) : androidx.lifecycle.AndroidViewModel(application) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val progressTracker = AtomicReference<ReadingProgressTracker?>(null)
    private val imageCache = PageImageCache(64L * 1024 * 1024)
    private val httpClient = AppHttpClientFactory.create(AppHttpClientFactory.createDispatcher())
    private val cookieJars = PerSourceCookieJarRegistry()
    private val network = SourceNetworkExecutor(baseClient = httpClient, cookieJars = cookieJars)
    private val authProvider = SourceCookieImageAuth(cookieJars)
    private val diskCache = comicImageDiskCache(getApplication())
    private val imagePipeline = CoilComicImagePipeline(httpClient, diskCache, authProvider)
    val imageLoader = comicImageLoader(getApplication(), authProvider, imagePipeline, diskCache)
    private val runtime = QuickJsRuntime(
        hostApi = SourceNetworkHostApi(network), appLocale = Locale.getDefault().toString(),
    )
    val sourceRepository = DefaultSourceRepository(
        SourcePackageStore(File(getApplication<android.app.Application>().filesDir, "sources")),
        runtime,
        QuickJsMetadataReader(),
        AndroidSourceScriptFetcher(getApplication()),
        onSourceChanged = { network.clearSource(it) },
    )
    val catalog = DefaultComicCatalog(sourceRepository, EngineSourceCore(runtime))
    val provider: PageProvider = SourcePageProvider(catalog, CoilPageImageSizer(imagePipeline))
    val decoderFactory: (DecodeStrategy) -> PageImageDecoder = { strategy ->
        val decoder = when (strategy) {
            DecodeStrategy.Sampled -> SampledPageImageDecoder()
            DecodeStrategy.Region -> RegionPageImageDecoder()
        }
        dev.veneranative.core.image.decode.PipelinePageImageDecoder(
            imagePipeline, CachingPageImageDecoder(decoder, imageCache),
        )
    }
    private val _history = kotlinx.coroutines.flow.MutableStateFlow<HistoryRepository?>(null)
    val history: kotlinx.coroutines.flow.StateFlow<HistoryRepository?> = _history
    private val _collection = kotlinx.coroutines.flow.MutableStateFlow<CollectionRepository?>(null)
    val collection: kotlinx.coroutines.flow.StateFlow<CollectionRepository?> = _collection
    init {
        scope.launch {
            val db = VeneraDatabaseFactory.get(getApplication())
            val repository = DefaultHistoryRepository(db)
            progressTracker.set(ReadingProgressTracker(repository, scope, clock = { System.currentTimeMillis() }))
            _history.value = repository
            // The shelf asks installed sources for chapter snapshots; the assembly layer is the only
            // place that can see both the repository and the catalog.
            _collection.value = DefaultCollectionRepository(db, ComicCatalogChapterProbe(catalog))
        }
    }
    fun flushProgress() { scope.launch { runCatching { progressTracker.get()?.flush() } } }
    override fun onCleared() {
        scope.launch(NonCancellable) {
            try { progressTracker.get()?.flush() } finally {
                runtime.close()
                imageLoader.shutdown()
                diskCache.shutdown()
                httpClient.dispatcher.cancelAll()
                httpClient.connectionPool.evictAll()
                imageCache.clear()
                scope.cancel()
            }
        }
    }
}
