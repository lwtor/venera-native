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
import dev.veneranative.core.navigation.detailsOriginAfterNavigation
import dev.veneranative.core.navigation.decodeAppRoute
import dev.veneranative.core.navigation.encode
import dev.veneranative.core.network.AppHttpClientFactory
import dev.veneranative.data.collection.CollectionRepository
import dev.veneranative.data.comic.DefaultComicCatalog
import dev.veneranative.data.comic.SourcePageProvider
import dev.veneranative.data.history.DefaultHistoryRepository
import dev.veneranative.data.history.HistoryRepository
import dev.veneranative.data.history.ReadingHistoryEntry
import dev.veneranative.data.history.ReadingProgressTracker
import dev.veneranative.data.download.DownloadRepository
import dev.veneranative.data.download.worker.DownloadWorkScheduler
import dev.veneranative.core.model.ChapterRef
import dev.veneranative.data.local.localReaderKey
import dev.veneranative.data.source.DefaultSourceRepository
import dev.veneranative.data.source.LocalFileScriptFetcher
import dev.veneranative.data.source.SourcePackageStore
import dev.veneranative.feature.details.DetailsRoute
import dev.veneranative.feature.explore.ExploreRoute
import dev.veneranative.feature.home.HomeRoute
import dev.veneranative.feature.library.LibraryRoute
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

class MainActivity : ComponentActivity() {

    private val graph by lazy {
        androidx.lifecycle.ViewModelProvider(this)[AppGraph::class.java]
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            VeneraNativeTheme {
                App(this@MainActivity, graph)
            }
        }
    }

    override fun onStop() {
        graph.flushProgress()
        super.onStop()
    }
}

@Composable
private fun App(
    activity: MainActivity,
    graph: AppGraph,
) {
    val historyRepository by graph.history.collectAsStateWithLifecycle()
    val collectionRepository by graph.collection.collectAsStateWithLifecycle()
    val localRepository by graph.local.collectAsStateWithLifecycle()
    val downloadRepository by graph.download.collectAsStateWithLifecycle()
    val appScope = graph.scope
    val progressTracker = graph.progressTracker
    val catalog = graph.catalog
    val sourceRepository = graph.sourceRepository
    val provider = graph.provider
    val decoderFactory = graph.decoderFactory
    val imageLoader = graph.imageLoader

    var route by rememberSaveable(stateSaver = AppRouteSaver) {
        mutableStateOf<AppRoute>(AppRoute.Home)
    }
    var detailsOrigin by rememberSaveable(stateSaver = AppRouteSaver) {
        mutableStateOf<AppRoute>(AppRoute.Home)
    }

    CompositionLocalProvider(LocalComicImageLoader provides imageLoader) {
        AppNavHost(
            route = route,
            onRouteChange = { next ->
                detailsOrigin = detailsOriginAfterNavigation(route, next, detailsOrigin)
                route = next
            },
            detailsOrigin = detailsOrigin,
            catalog = catalog,
            sourceRepository = sourceRepository,
            provider = provider,
            decoderFactory = decoderFactory,
            appScope = appScope,
            activity = activity,
            historyRepository = historyRepository,
            collectionRepository = collectionRepository,
            localRepository = localRepository,
            downloadRepository = downloadRepository,
            progressTracker = progressTracker,
        )
    }
}

@Composable
private fun AppNavHost(
    route: AppRoute,
    onRouteChange: (AppRoute) -> Unit,
    detailsOrigin: AppRoute,
    catalog: DefaultComicCatalog,
    sourceRepository: DefaultSourceRepository,
    provider: PageProvider,
    decoderFactory: ((DecodeStrategy) -> PageImageDecoder)?,
    appScope: CoroutineScope,
    activity: MainActivity,
    historyRepository: HistoryRepository?,
    collectionRepository: CollectionRepository?,
    localRepository: dev.veneranative.data.local.LocalComicRepository?,
    downloadRepository: DownloadRepository?,
    progressTracker: AtomicReference<ReadingProgressTracker?>,
) {
    var scriptSelection by remember { mutableStateOf<((String) -> Unit)?>(null) }
    var pendingLocalImport by remember { mutableStateOf<((String) -> Unit)?>(null) }
    var pendingArchiveImport by remember { mutableStateOf<((String) -> Unit)?>(null) }
    val localTreePicker = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocumentTree(),
    ) { uri -> uri?.toString()?.let { pendingLocalImport?.invoke(it) }; pendingLocalImport = null }
    val localArchivePicker = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.toString()?.let { pendingArchiveImport?.invoke(it) }; pendingArchiveImport = null }
    val scriptPicker = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument(),
    ) { uri ->
        uri?.toString()?.let { scriptSelection?.invoke(it) }
        scriptSelection = null
    }
    when (val current = route) {
        AppRoute.Home -> HomeRoute(
            onOpenReader = { },
            onOpenSources = { onRouteChange(AppRoute.Sources) },
            onOpenExplore = { onRouteChange(AppRoute.Explore(null)) },
            onOpenSearch = { onRouteChange(AppRoute.Search(null)) },
            onOpenLibrary = { onRouteChange(AppRoute.Library) },
        )

        AppRoute.Library -> {
            val collection = collectionRepository
            if (collection == null || localRepository == null || downloadRepository == null) {
                androidx.compose.material3.CircularProgressIndicator()
            } else {
                LibraryRoute(
                    collection = collection,
                    localRepository = localRepository,
                    onRequestLocalImport = { consume -> pendingLocalImport = consume; localTreePicker.launch(null) },
                    onRequestArchiveImport = { consume -> pendingArchiveImport = consume; localArchivePicker.launch(arrayOf("application/zip", "application/x-7z-compressed", "application/octet-stream")) },
                    onOpenComic = { onRouteChange(AppRoute.ComicDetails(it)) },
                    onOpenLocalChapter = { comicId, chapterId -> onRouteChange(AppRoute.Reader(localReaderKey(comicId, chapterId))) },
                    downloads = downloadRepository,
                    onScheduleDownloads = { DownloadWorkScheduler.start(activity, expedited = true) },
                    onBack = { onRouteChange(AppRoute.Home) },
                )
            }
        }

        AppRoute.Sources -> SourcesRoute(
            repository = sourceRepository,
            onBack = { onRouteChange(AppRoute.Home) },
            onRequestScript = { consume ->
                scriptSelection = consume
                scriptPicker.launch(arrayOf("application/javascript", "text/javascript", "text/plain"))
            },
        )

        is AppRoute.Explore -> ExploreRoute(
            catalog = catalog,
            onOpenComic = { onRouteChange(AppRoute.ComicDetails(it)) },
            onBack = { onRouteChange(AppRoute.Home) },
        )

        is AppRoute.Search -> SearchRoute(
            catalog = catalog,
            onOpenComic = { onRouteChange(AppRoute.ComicDetails(it)) },
            onBack = { onRouteChange(AppRoute.Home) },
        )

        is AppRoute.ComicDetails -> DetailsRoute(
            catalog = catalog,
            comicKey = current.comicKey,
            collection = collectionRepository,
            downloads = downloadRepository,
            onOpenChapter = { onRouteChange(AppRoute.Reader(ChapterRef.Remote(it))) },
            onScheduleDownloads = { DownloadWorkScheduler.start(activity, expedited = true) },
            onBack = { onRouteChange(detailsOrigin) },
        )

        is AppRoute.Reader -> {
            val tracker = progressTracker.get()
            if (historyRepository == null || tracker == null) {
                androidx.compose.material3.CircularProgressIndicator()
            } else {
                val session = remember(current.chapter, historyRepository, tracker) {
                    dev.veneranative.data.history.ReaderProgressSession(
                        current.chapter, historyRepository, tracker, { System.currentTimeMillis() },
                    )
                }
                ReaderRoute(
                    chapter = current.chapter, provider = provider,
                    onBack = {
                        when (val chapter = current.chapter) {
                            is ChapterRef.Local -> onRouteChange(AppRoute.Library)
                            is ChapterRef.Remote -> onRouteChange(AppRoute.ComicDetails(chapter.key.comicKey))
                        }
                    },
                    decoderFactory = decoderFactory, progress = session,
                    onExit = { appScope.launch { runCatching { tracker.flush() } } },
                )
            }
        }
    }
}

/** Keeps the current route across process death; the encoding itself lives in `:core:navigation`. */
private val AppRouteSaver: Saver<AppRoute, String> = Saver(
    save = { it.encode() },
    restore = { decodeAppRoute(it) ?: AppRoute.Home },
)

private fun decoderFor(strategy: DecodeStrategy, cache: PageImageCache): PageImageDecoder {
    val decoder: PageImageDecoder = when (strategy) {
        DecodeStrategy.Sampled -> SampledPageImageDecoder()
        DecodeStrategy.Region -> RegionPageImageDecoder()
    }
    return CachingPageImageDecoder(delegate = decoder, cache = cache)
}
