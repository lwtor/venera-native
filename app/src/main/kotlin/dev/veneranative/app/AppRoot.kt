package dev.veneranative.app

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScaffoldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.veneranative.core.image.compose.LocalComicImageLoader
import dev.veneranative.core.image.decode.PageImageDecoder
import dev.veneranative.core.image.tiling.DecodeStrategy
import dev.veneranative.core.model.ChapterRef
import dev.veneranative.core.model.PageProvider
import dev.veneranative.core.navigation.AppRoute
import dev.veneranative.core.navigation.backDestination
import dev.veneranative.core.navigation.decodeAppRoute
import dev.veneranative.core.navigation.detailsOriginAfterNavigation
import dev.veneranative.core.navigation.encode
import dev.veneranative.data.collection.CollectionRepository
import dev.veneranative.data.comic.DefaultComicCatalog
import dev.veneranative.data.download.DownloadRepository
import dev.veneranative.data.download.worker.DownloadWorkScheduler
import dev.veneranative.data.history.HistoryRepository
import dev.veneranative.data.history.ReadingProgressTracker
import dev.veneranative.data.local.localReaderKey
import dev.veneranative.data.source.DefaultSourceRepository
import dev.veneranative.feature.backup.BackupRoute
import dev.veneranative.feature.details.DetailsRoute
import dev.veneranative.feature.explore.ExploreRoute
import dev.veneranative.feature.home.HomeRoute
import dev.veneranative.feature.home.toChapterRef
import dev.veneranative.feature.library.LibraryRoute
import dev.veneranative.feature.profile.AboutRoute
import dev.veneranative.feature.profile.InstalledAppInfo
import dev.veneranative.feature.profile.ProfileRoute
import dev.veneranative.feature.reader.ReaderRoute
import dev.veneranative.feature.search.SearchRoute
import dev.veneranative.feature.sources.SourcesRoute
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

@Composable
internal fun AppRoot(
    activity: MainActivity,
    graph: AppGraph,
) {
    val historyRepository by graph.history.collectAsStateWithLifecycle()
    val collectionRepository by graph.collection.collectAsStateWithLifecycle()
    val localRepository by graph.local.collectAsStateWithLifecycle()
    val downloadRepository by graph.download.collectAsStateWithLifecycle()
    val searchHistoryRepository by graph.searchHistory.collectAsStateWithLifecycle()
    val screenPreferences by graph.screenPreferences.collectAsStateWithLifecycle()
    val backupFactory by graph.backupFactory.collectAsStateWithLifecycle()
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
            backupFactory = backupFactory,
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
    backupFactory: dev.veneranative.data.backup.GitHubBackupGatewayFactory?,
) {
    val backRoute = backDestination(route, detailsOrigin, readerOrigin)
    val routeStateHolder = rememberSaveableStateHolder()
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
    val isMainTab = route == AppRoute.Home || route == AppRoute.Library || route == AppRoute.Profile

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        contentWindowInsets = if (isMainTab || route == AppRoute.Downloads) {
            ScaffoldDefaults.contentWindowInsets
        } else {
            WindowInsets(0.dp)
        },
        bottomBar = {
            if (isMainTab) {
                NavigationBar {
                    NavigationBarItem(
                        selected = route == AppRoute.Home,
                        onClick = { onRouteChange(AppRoute.Home) },
                        icon = { Icon(Icons.Default.Home, null) },
                        label = { Text("首页") },
                        modifier = Modifier.testTag("root_tab_home"),
                    )
                    NavigationBarItem(
                        selected = route == AppRoute.Library,
                        onClick = { onRouteChange(AppRoute.Library) },
                        icon = { Icon(BookshelfIcon, null) },
                        label = { Text("书架") },
                        modifier = Modifier.testTag("root_tab_library"),
                    )
                    NavigationBarItem(
                        selected = route == AppRoute.Profile,
                        onClick = { onRouteChange(AppRoute.Profile) },
                        icon = { Icon(Icons.Default.Person, null) },
                        label = { Text("我的") },
                        modifier = Modifier.testTag("root_tab_profile"),
                    )
                }
            }
        },
    ) { contentPadding ->
        Box(modifier = Modifier.fillMaxSize().padding(contentPadding)) {
            routeStateHolder.SaveableStateProvider(route.encode()) {
                when (val current = route) {
                    AppRoute.Home -> HomeRoute(
                        history = historyRepository,
                        collection = collectionRepository,
                        localRepository = localRepository,
                        onResumeReading = { entry ->
                            onRouteChange(AppRoute.Reader(entry.toChapterRef()))
                        },
                        onOpenSources = { onRouteChange(AppRoute.Sources) },
                        onOpenSearch = { onRouteChange(AppRoute.Search(null)) },
                        onOpenLibrary = { onRouteChange(AppRoute.Library) },
                        onOpenBackup = { onRouteChange(AppRoute.Profile) },
                    )

                    AppRoute.Backup -> BackupRoute(
                        factory = backupFactory,
                        onBack = onBack,
                    )

                    AppRoute.Profile -> ProfileRoute(
                        factory = backupFactory,
                        onOpenBackup = { onRouteChange(AppRoute.Backup) },
                        onOpenAbout = { onRouteChange(AppRoute.About) },
                    )

                    AppRoute.About -> {
                        val info = remember(activity) {
                            @Suppress("DEPRECATION")
                            val installed = activity.packageManager.getPackageInfo(activity.packageName, 0)
                            InstalledAppInfo(installed.versionName ?: "未知",
                                androidx.core.content.pm.PackageInfoCompat.getLongVersionCode(installed))
                        }
                        AboutRoute(info, onBack)
                    }

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
                                onOpenDownloadedChapter = { chapter -> onRouteChange(AppRoute.Reader(chapter)) },
                                downloads = downloadRepository,
                                screenPreferences = screenPreferences,
                                onScheduleDownloads = { DownloadWorkScheduler.start(activity, expedited = true) },
                                onOpenDownloads = { onRouteChange(AppRoute.Downloads) },
                            )
                        }
                    }

                    AppRoute.Downloads -> {
                        val collection = collectionRepository
                        if (collection == null || localRepository == null || downloadRepository == null) {
                            androidx.compose.material3.CircularProgressIndicator()
                        } else {
                            LibraryRoute(
                                collection = collection,
                                localRepository = localRepository,
                                downloads = downloadRepository,
                                screenPreferences = screenPreferences,
                                standaloneDownloads = true,
                                onBackFromDownloads = { onRouteChange(AppRoute.Library) },
                                onOpenComic = { onRouteChange(AppRoute.ComicDetails(it)) },
                                onOpenLocalChapter = { comicId, chapterId -> onRouteChange(AppRoute.Reader(localReaderKey(comicId, chapterId))) },
                                onOpenDownloadedChapter = { chapter -> onRouteChange(AppRoute.Reader(chapter)) },
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
                        onOpenDownloads = { onRouteChange(AppRoute.Downloads) },
                        onBack = onBack,
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
}

/** Keeps the current route across process death; the encoding itself lives in `:core:navigation`. */
private val AppRouteSaver: Saver<AppRoute, String> = Saver(
    save = { it.encode() },
    restore = { decodeAppRoute(it) ?: AppRoute.Home },
)
