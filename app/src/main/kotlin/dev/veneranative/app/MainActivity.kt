package dev.veneranative.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScaffoldDefaults
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
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
import dev.veneranative.core.navigation.backDestination
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
import dev.veneranative.feature.home.toChapterRef
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
        installSplashScreen()
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
    val searchHistoryRepository by graph.searchHistory.collectAsStateWithLifecycle()
    val screenPreferences by graph.screenPreferences.collectAsStateWithLifecycle()
    val appScope = graph.scope
    val progressTracker = graph.progressTracker
    val catalog = graph.catalog
    val sourceRepository = graph.sourceRepository
    val sourceCatalogRepository = graph.sourceCatalogRepository
    val provider = graph.provider
    val decoderFactory = graph.decoderFactory
    val imageLoader = graph.imageLoader

    var route by rememberSaveable(stateSaver = AppRouteSaver) {
        mutableStateOf<AppRoute>(AppRoute.Home)
    }
    var detailsOrigin by rememberSaveable(stateSaver = AppRouteSaver) {
        mutableStateOf<AppRoute>(AppRoute.Home)
    }
    var readerOrigin by rememberSaveable(stateSaver = AppRouteSaver) {
        mutableStateOf<AppRoute>(AppRoute.Home)
    }

    CompositionLocalProvider(LocalComicImageLoader provides imageLoader) {
        AppNavHost(
            route = route,
            onRouteChange = { next ->
                detailsOrigin = detailsOriginAfterNavigation(route, next, detailsOrigin)
                if (next is AppRoute.Reader && route !is AppRoute.Reader) readerOrigin = route
                route = next
            },
            detailsOrigin = detailsOrigin,
            readerOrigin = readerOrigin,
            catalog = catalog,
            sourceRepository = sourceRepository,
            sourceCatalogRepository = sourceCatalogRepository,
            provider = provider,
            decoderFactory = decoderFactory,
            appScope = appScope,
            activity = activity,
            historyRepository = historyRepository,
            collectionRepository = collectionRepository,
            localRepository = localRepository,
            downloadRepository = downloadRepository,
            searchHistoryRepository = searchHistoryRepository,
            screenPreferences = screenPreferences,
            progressTracker = progressTracker,
        )
    }
}

@Composable
private fun AppNavHost(
    route: AppRoute,
    onRouteChange: (AppRoute) -> Unit,
    detailsOrigin: AppRoute,
    readerOrigin: AppRoute,
    catalog: DefaultComicCatalog,
    sourceRepository: DefaultSourceRepository,
    sourceCatalogRepository: dev.veneranative.data.source.SourceCatalogRepository,
    provider: PageProvider,
    decoderFactory: ((DecodeStrategy) -> PageImageDecoder)?,
    appScope: CoroutineScope,
    activity: MainActivity,
    historyRepository: HistoryRepository?,
    collectionRepository: CollectionRepository?,
    localRepository: dev.veneranative.data.local.LocalComicRepository?,
    downloadRepository: DownloadRepository?,
    searchHistoryRepository: dev.veneranative.data.search.SearchHistoryRepository?,
    screenPreferences: dev.veneranative.data.settings.ScreenPreferenceRepository?,
    progressTracker: AtomicReference<ReadingProgressTracker?>,
) {
    val backRoute = backDestination(route, detailsOrigin, readerOrigin)
    val onBack: () -> Unit = {
        val destination = backRoute ?: AppRoute.Home
        if (route is AppRoute.Reader) {
            appScope.launch {
                progressTracker.get()?.let { tracker -> runCatching { tracker.flush() } }
                onRouteChange(destination)
            }
        } else {
            onRouteChange(destination)
        }
    }
    BackHandler(enabled = backRoute != null, onBack = onBack)

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
    val isMainTab = route == AppRoute.Home ||
        route == AppRoute.Library ||
        route == AppRoute.Sources ||
        route is AppRoute.Explore

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        contentWindowInsets = if (route is AppRoute.ComicDetails || route is AppRoute.Reader) {
            WindowInsets(0.dp)
        } else {
            ScaffoldDefaults.contentWindowInsets
        },
        bottomBar = {
            if (isMainTab) {
                NavigationBar {
                    NavigationBarItem(
                        selected = route == AppRoute.Home,
                        onClick = { onRouteChange(AppRoute.Home) },
                        icon = { Text("⌂") },
                        label = { Text("首页") },
                        modifier = Modifier.testTag("root_tab_home"),
                    )
                    NavigationBarItem(
                        selected = route is AppRoute.Explore,
                        onClick = { onRouteChange(AppRoute.Explore(null)) },
                        icon = { Text("◉") },
                        label = { Text("探索") },
                        modifier = Modifier.testTag("root_tab_explore"),
                    )
                    NavigationBarItem(
                        selected = route == AppRoute.Library,
                        onClick = { onRouteChange(AppRoute.Library) },
                        icon = { Text("▤") },
                        label = { Text("书架") },
                        modifier = Modifier.testTag("root_tab_library"),
                    )
                    NavigationBarItem(
                        selected = route == AppRoute.Sources,
                        onClick = { onRouteChange(AppRoute.Sources) },
                        icon = { Text("＋") },
                        label = { Text("来源") },
                        modifier = Modifier.testTag("root_tab_sources"),
                    )
                }
            }
        },
    ) { contentPadding ->
        Box(modifier = Modifier.fillMaxSize().padding(contentPadding)) {
            when (val current = route) {
        AppRoute.Home -> HomeRoute(
            history = historyRepository,
            collection = collectionRepository,
            localRepository = localRepository,
            onResumeReading = { entry ->
                onRouteChange(AppRoute.Reader(entry.toChapterRef()))
            },
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
                            onOpenLocalChapter = { comicId, chapterId -> onRouteChange(AppRoute.Reader(localReaderKey(comicId, chapterId))) },
                    downloads = downloadRepository,
                    screenPreferences = screenPreferences,
                    onScheduleDownloads = { DownloadWorkScheduler.start(activity, expedited = true) },
                )
            }
        }

        AppRoute.Sources -> SourcesRoute(
            repository = sourceRepository,
            catalogRepository = sourceCatalogRepository,
            onBack = onBack,
            onRequestScript = { consume ->
                scriptSelection = consume
                scriptPicker.launch(arrayOf("application/javascript", "text/javascript", "text/plain"))
            },
        )

        is AppRoute.Explore -> ExploreRoute(
            catalog = catalog,
            onOpenComic = { onRouteChange(AppRoute.ComicDetails(it)) },
            onBack = onBack,
        )

        is AppRoute.Search -> SearchRoute(
            catalog = catalog,
            historyRepository = searchHistoryRepository,
            screenPreferences = screenPreferences,
            onOpenComic = { onRouteChange(AppRoute.ComicDetails(it)) },
            onBack = onBack,
        )

        is AppRoute.ComicDetails -> DetailsRoute(
            catalog = catalog,
            comicKey = current.comicKey,
            collection = collectionRepository,
            downloads = downloadRepository,
            history = historyRepository,
            screenPreferences = screenPreferences,
            onOpenChapter = { onRouteChange(AppRoute.Reader(ChapterRef.Remote(it.key, it.group))) },
            onScheduleDownloads = { DownloadWorkScheduler.start(activity, expedited = true) },
            onBack = onBack,
            onOpenComic = { onRouteChange(AppRoute.ComicDetails(it)) },
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
                    onBack = onBack,
                    decoderFactory = decoderFactory, progress = session,
                    progressFactory = { target ->
                        if (target == current.chapter) session
                        else dev.veneranative.data.history.ReaderProgressSession(
                            target, historyRepository, tracker, { System.currentTimeMillis() },
                        )
                    },
                    onExit = { appScope.launch { runCatching { tracker.flush() } } },
                )
            }
        }
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
