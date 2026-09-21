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
import dev.veneranative.data.history.DefaultHistoryRepository
import dev.veneranative.data.history.HistoryRepository
import dev.veneranative.data.history.ReadingHistoryEntry
import dev.veneranative.data.history.ReadingProgressTracker
import dev.veneranative.data.source.DefaultSourceRepository
import dev.veneranative.data.source.LocalFileScriptFetcher
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

class MainActivity : ComponentActivity() {

    /**
     * Outlives any single screen: a progress write must survive leaving the reader, so its scope and
     * handle live with the activity rather than with the composition that happened to start it.
     */
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val progressTracker = AtomicReference<ReadingProgressTracker?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            VeneraNativeTheme {
                App(this@MainActivity, appScope, progressTracker)
            }
        }
    }

    override fun onStop() {
        // What makes throttling safe: the newest page is still in memory, and this is the last
        // moment the process is guaranteed alive. `NonCancellable` because a flush must not be
        // dropped by lifecycle teardown.
        progressTracker.get()?.let { tracker ->
            appScope.launch(NonCancellable) { tracker.flush() }
        }
        super.onStop()
    }

    override fun onDestroy() {
        appScope.cancel()
        super.onDestroy()
    }
}

@Composable
private fun App(
    activity: MainActivity,
    appScope: CoroutineScope,
    progressTracker: AtomicReference<ReadingProgressTracker?>,
) {
    // Assembly layer: features receive their dependencies from here, so no feature has to know
    // about storage, the engine or another feature.
    val context = LocalContext.current.applicationContext
    val imageCache = remember { PageImageCache(DEFAULT_IMAGE_CACHE_BYTES) }

    // One OkHttp dispatcher and one cookie registry for both the source calls and the images:
    // sharing them is what lets a chapter's cookies unlock that chapter's pages.
    val httpClient = remember { AppHttpClientFactory.create(AppHttpClientFactory.createDispatcher()) }
    val cookieJars = remember { PerSourceCookieJarRegistry() }
    val authProvider: ComicImageAuthProvider = remember(cookieJars) {
        SourceCookieImageAuth(cookieJars)
    }
    val diskCache = remember(context) { comicImageDiskCache(context) }
    val imagePipeline = remember(httpClient, context) { CoilComicImagePipeline(httpClient, diskCache, authProvider) }
    val imageLoader = remember(context, authProvider, imagePipeline) {
        comicImageLoader(context, authProvider, imagePipeline, diskCache)
    }

    // One runtime serves the whole app: sources are loaded into it, and both the repository and the
    // catalog above it talk to the same loaded instances.
    val runtime = remember(cookieJars, httpClient) {
        QuickJsRuntime(
            hostApi = SourceNetworkHostApi(SourceNetworkExecutor(baseClient = httpClient, cookieJars = cookieJars)),
            appLocale = Locale.getDefault().toString(),
        )
    }
    val sourceRepository = remember(context, runtime) {
        DefaultSourceRepository(
            store = SourcePackageStore(File(context.filesDir, "sources")),
            runtime = runtime,
            metadataReader = QuickJsMetadataReader(),
            fetcher = LocalFileScriptFetcher(),
        )
    }
    val catalog = remember(sourceRepository, runtime) {
        DefaultComicCatalog(sources = sourceRepository, core = EngineSourceCore(runtime))
    }

    // Real pages: the sizes `ComicPage` needs come from the image pipeline, which is what makes a
    // source-backed chapter renderable at all.
    val provider: PageProvider = remember(catalog, imagePipeline) {
        SourcePageProvider(catalog = catalog, sizer = CoilPageImageSizer(imagePipeline))
    }
    val decoderFactory: ((DecodeStrategy) -> PageImageDecoder)? = remember(imageCache) {
        { strategy -> decoderFor(strategy, imageCache) }
    }

    // Room is opened asynchronously, so reading stays available before it: history precision is not
    // worth blocking the first frame on disk I/O.
    val database by produceState<VeneraDatabase?>(initialValue = null, context) {
        value = VeneraDatabaseFactory.get(context)
    }
    val historyRepository: HistoryRepository? = remember(database) {
        database?.let { DefaultHistoryRepository(it.readingHistoryDao(), it.readingProgressDao()) }
    }
    LaunchedEffect(historyRepository, appScope) {
        historyRepository?.let { repository ->
            progressTracker.set(
                ReadingProgressTracker(
                    repository = repository,
                    scope = appScope,
                    clock = { System.currentTimeMillis() },
                ),
            )
        }
    }

    var route by rememberSaveable(stateSaver = AppRouteSaver) {
        mutableStateOf<AppRoute>(AppRoute.Home)
    }

    CompositionLocalProvider(LocalComicImageLoader provides imageLoader) {
        AppNavHost(
            route = route,
            onRouteChange = { route = it },
            catalog = catalog,
            sourceRepository = sourceRepository,
            provider = provider,
            decoderFactory = decoderFactory,
            appScope = appScope,
            activity = activity,
            historyRepository = historyRepository,
            progressTracker = progressTracker,
        )
    }
}

@Composable
private fun AppNavHost(
    route: AppRoute,
    onRouteChange: (AppRoute) -> Unit,
    catalog: DefaultComicCatalog,
    sourceRepository: DefaultSourceRepository,
    provider: PageProvider,
    decoderFactory: ((DecodeStrategy) -> PageImageDecoder)?,
    appScope: CoroutineScope,
    activity: MainActivity,
    historyRepository: HistoryRepository?,
    progressTracker: AtomicReference<ReadingProgressTracker?>,
) {
    when (val current = route) {
        AppRoute.Home -> HomeRoute(
            onOpenReader = { },
            onOpenSources = { onRouteChange(AppRoute.Sources) },
            onOpenExplore = { onRouteChange(AppRoute.Explore(null)) },
            onOpenSearch = { onRouteChange(AppRoute.Search(null)) },
        )

        AppRoute.Sources -> SourcesRoute(
            repository = sourceRepository,
            onBack = { onRouteChange(AppRoute.Home) },
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
            onOpenChapter = { onRouteChange(AppRoute.Reader(it)) },
            onBack = { onRouteChange(AppRoute.Home) },
        )

        is AppRoute.Reader -> {
            val startPage by resumePageOf(current.chapter, historyRepository)
            ReaderRoute(
                chapter = current.chapter,
                provider = provider,
                onBack = { onRouteChange(AppRoute.Home) },
                decoderFactory = decoderFactory,
                startPageIndex = startPage,
                progressRecorder = historyRepository?.let { repository ->
                    recorderFor(current.chapter, repository, progressTracker)
                },
            )
        }
    }
}

/** Where a resumed chapter should open, or the first page when nothing was recorded. */
@Composable
private fun resumePageOf(
    chapter: ChapterKey,
    repository: HistoryRepository?,
): State<Int> =
    produceState(initialValue = 0, chapter, repository) {
        value = repository?.progress(chapter.comicKey)?.pageIndex ?: 0
    }

/**
 * Turns page turns into throttled writes.
 *
 * The chapter's own title comes from the provider: it is display text, never a cache key, so nothing
 * breaks when a source returns something odd.
 */
private fun recorderFor(
    chapter: ChapterKey,
    repository: HistoryRepository,
    progressTracker: AtomicReference<ReadingProgressTracker?>,
): ReaderViewModel.ChapterPageRecorder = ReaderViewModel.ChapterPageRecorder { chapterTitle, pageIndex, pageCount ->
    progressTracker.get()?.onPageChanged(
        ReadingHistoryEntry(
            comicKey = chapter.comicKey,
            comicTitle = chapter.remoteId.value,
            chapterId = chapter.remoteId,
            chapterTitle = chapterTitle,
            coverUrl = null,
            pageIndex = pageIndex,
            pageCount = pageCount,
            updatedAtEpochMillis = System.currentTimeMillis(),
        ),
    )
}

private const val DEFAULT_IMAGE_CACHE_BYTES = 64L * 1024 * 1024

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
