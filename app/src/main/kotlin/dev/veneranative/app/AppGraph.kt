package dev.veneranative.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.content.pm.PackageInfoCompat
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
import dev.veneranative.core.image.CoilPageImageSizer
import dev.veneranative.core.image.ComicImageAuthProvider
import dev.veneranative.core.image.compose.LocalComicImageLoader
import dev.veneranative.core.image.decode.CachingPageImageDecoder
import dev.veneranative.core.image.decode.PageImageCache
import dev.veneranative.core.image.decode.PageImageDecoder
import dev.veneranative.core.image.decode.RegionPageImageDecoder
import dev.veneranative.core.image.decode.SampledPageImageDecoder
import dev.veneranative.core.image.tiling.DecodeStrategy
import dev.veneranative.core.model.ChapterKey
import dev.veneranative.core.model.ChapterNeighbors
import dev.veneranative.core.model.ChapterRef
import dev.veneranative.core.model.ComicKey
import dev.veneranative.core.model.PageProvider
import dev.veneranative.core.model.RemoteChapterId
import dev.veneranative.core.model.RemoteComicId
import dev.veneranative.core.model.SourceId
import dev.veneranative.source.api.SourceOutcome
import java.net.URI
import dev.veneranative.core.navigation.AppRoute
import dev.veneranative.core.navigation.decodeAppRoute
import dev.veneranative.core.navigation.encode
import dev.veneranative.data.comic.DefaultComicCatalog
import dev.veneranative.data.comic.SourcePageProvider
import dev.veneranative.data.collection.ComicCatalogChapterProbe
import dev.veneranative.data.download.DownloadEnvironment
import dev.veneranative.data.download.DownloadRepository
import dev.veneranative.data.download.OfflineFirstPageProvider
import dev.veneranative.data.collection.CollectionRepository
import dev.veneranative.data.backup.GitHubBackupGatewayFactory
import dev.veneranative.data.backup.RoomBackupRepository
import dev.veneranative.data.collection.DefaultCollectionRepository
import dev.veneranative.data.history.DefaultHistoryRepository
import dev.veneranative.data.history.HistoryRepository
import dev.veneranative.data.history.ReadingHistoryEntry
import dev.veneranative.data.history.ReadingProgressTracker
import dev.veneranative.data.local.AndroidSafTreeAccess
import dev.veneranative.data.local.DefaultLocalComicRepository
import dev.veneranative.data.local.AndroidSafArchiveAccess
import dev.veneranative.data.local.DefaultLocalPageSource
import dev.veneranative.data.local.LocalFirstPageProvider
import dev.veneranative.data.local.LocalPageCache
import dev.veneranative.data.local.LocalPageMaterializer
import dev.veneranative.data.search.DefaultSearchHistoryRepository
import dev.veneranative.data.search.SearchHistoryRepository
import dev.veneranative.core.model.SplitPreferenceRepository
import dev.veneranative.data.settings.DefaultScreenPreferenceRepository
import dev.veneranative.data.settings.DefaultSplitPreferenceRepository
import dev.veneranative.data.settings.ScreenPreferenceRepository
import dev.veneranative.data.update.SelfUpdateService
import dev.veneranative.data.update.UpdateEnvironment
import dev.veneranative.data.local.LocalComicRepository
import dev.veneranative.data.source.DefaultSourceRepository
import dev.veneranative.data.source.AndroidSourceScriptFetcher
import dev.veneranative.data.source.HttpSourceCatalogRepository
import dev.veneranative.data.source.SourcePackageStore
import dev.veneranative.data.source.AndroidSourcePrivateSettingsStore
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
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class AppGraph(application: android.app.Application) : androidx.lifecycle.AndroidViewModel(application) {
    /**
     * The shared half of the graph. It lives on the Application because the download worker runs in
     * this process too, and it must fetch pages with the same client, cookies and pipeline the
     * screens use — see `VeneraApplication`.
     */
    private val host = application as VeneraApplication
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val progressTracker = AtomicReference<ReadingProgressTracker?>(null)
    private val imageCache = PageImageCache(64L * 1024 * 1024)
    private val httpClient = host.httpClient
    private val cookieJars = host.cookieJars
    private val debugNetworkTraceEnabled =
        getApplication<android.app.Application>().applicationInfo.flags and
            android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0
    private val localKomiicNetworkLog =
        if (debugNetworkTraceEnabled) LocalKomiicNetworkLog(getApplication<android.app.Application>().filesDir) else null
    private val localSearchStartupLog =
        if (debugNetworkTraceEnabled) LocalSearchStartupLog(getApplication<android.app.Application>().filesDir) else null
    private val localOfflineReaderLog =
        if (debugNetworkTraceEnabled) LocalOfflineReaderLog(getApplication<android.app.Application>().filesDir) else null
    init {
        // The self-update download runs in a WorkManager worker that may outlive any screen; it
        // reaches the shared client through this process-wide hook rather than a captured graph.
        UpdateEnvironment.configure(httpClient)
        if (debugNetworkTraceEnabled) {
            val selectedProxy = runCatching {
                host.networkProxySelector.select(URI("https://komiic.com")).firstOrNull()
            }.getOrNull()
            localKomiicNetworkLog?.append(
                "source=Komiic method=POST host=komiic.com stage=proxySelection selected=$selectedProxy",
            )
        }
    }
    private val network = SourceNetworkExecutor(
        baseClient = httpClient,
        cookieJars = cookieJars,
        diagnosticSourceIds = if (debugNetworkTraceEnabled) {
            setOf(SourceId("Komiic"), SourceId("picacg"), SourceId("zaimanhua"))
        } else emptySet(),
        onNetworkTrace = { sourceId, message ->
            if (sourceId == SourceId("Komiic")) localKomiicNetworkLog?.append(message)
            android.util.Log.d("VeneraSourceNet", message)
        },
        onRequestFailure = { source, error ->
            android.util.Log.w("VeneraSource", "Network request failed: source=${source.value} error=$error")
        },
    )
    private val authProvider = host.authProvider
    private val diskCache = host.diskCache
    private val imagePipeline = host.imagePipeline
    val imageLoader = host.imageLoader
    private val runtime = QuickJsRuntime(
        hostApi = SourceNetworkHostApi(network), appLocale = Locale.getDefault().toString(),
    )
    private val comicSourceNamesState = kotlinx.coroutines.flow.MutableStateFlow<Map<String, String>>(emptyMap())
    val comicSourceNames: kotlinx.coroutines.flow.StateFlow<Map<String, String>> = comicSourceNamesState
    private val sourcePackageStore = SourcePackageStore(File(getApplication<android.app.Application>().filesDir, "sources"))
    private val sourcePrivateSettings = AndroidSourcePrivateSettingsStore(getApplication())
    private fun refreshComicSourceNames() {
        comicSourceNamesState.value = sourcePackageStore.list().associate { it.sourceId.value to it.name }
    }
    val sourceRepository = DefaultSourceRepository(
        sourcePackageStore,
        runtime,
        QuickJsMetadataReader(),
        AndroidSourceScriptFetcher(getApplication(), httpClient),
        privateSettings = sourcePrivateSettings,
        onSourceChanged = { network.clearSource(it); scope.launch { refreshComicSourceNames() } },
    )
    val sourceCatalogRepository = HttpSourceCatalogRepository(httpClient)
    val selfUpdateService = SelfUpdateService(
        getApplication(), httpClient, SelfUpdateConfiguration.REPOSITORY_OWNER, SelfUpdateConfiguration.REPOSITORY_NAME,
        SelfUpdateConfiguration.FILE_PROVIDER_AUTHORITY,
    )
    init {
        // Check for updates once at startup and prefetch the APK in the background. The profile
        // badge observes `selfUpdateService.updateStatus`, so a discovered update shows up without
        // the user opening the About screen.
        scope.launch {
            val installedVersionCode = runCatching {
                val app = getApplication<android.app.Application>()
                @Suppress("DEPRECATION")
                val info = app.packageManager.getPackageInfo(app.packageName, 0)
                PackageInfoCompat.getLongVersionCode(info)
            }.getOrNull() ?: return@launch
            selfUpdateService.checkAndPrefetch(installedVersionCode)
        }
    }
    val catalog = DefaultComicCatalog(
        sourceRepository,
        EngineSourceCore(runtime, onCallFailure = { source, member, error ->
            val safeError = if (debugNetworkTraceEnabled) error else error.substringBefore(':')
            android.util.Log.w("VeneraSource", "Source call failed: source=${source.value} member=$member error=$safeError")
        }, includeComicIdsInFailureDiagnostics = debugNetworkTraceEnabled,
            onSourceDataChanged = sourcePrivateSettings::writeSourceData),
        onDiscoveryTiming = { sourceCount, installMillis, capabilityMillis ->
            localSearchStartupLog?.append(
                "stage=source_discovery sources=$sourceCount installedMs=$installMillis capabilitiesMs=$capabilityMillis",
            )
        },
    )
    private val sourceRuntimeWarmup = scope.launch {
        val startedAt = android.os.SystemClock.elapsedRealtime()
        refreshComicSourceNames()
        val installed = runCatching { sourceRepository.installed() }.getOrDefault(emptyList())
        localSearchStartupLog?.append(
            "stage=source_runtime_warmup sources=${installed.size} elapsedMs=${android.os.SystemClock.elapsedRealtime() - startedAt}",
        )
    }
    private val sourcePageProvider: PageProvider = SourcePageProvider(
        catalog, CoilPageImageSizer(imagePipeline), prefetchScope = scope,
    )
    private val sourceChapterListCache =
        java.util.concurrent.ConcurrentHashMap<ComicKey, Pair<Long, List<dev.veneranative.core.model.Chapter>>>()
    private val offlineProvider: PageProvider = OfflineFirstPageProvider(
        downloads = { _download.value ?: _download.filterNotNull().first() },
        layout = DownloadEnvironment.get(getApplication()).layout(),
        source = sourcePageProvider,
        onDiagnostic = { event -> localOfflineReaderLog?.append(event) },
        chapterNeighbors = ::sourceChapterNeighbors,
    )
    private val localArchiveAccess = AndroidSafArchiveAccess(getApplication())
    private val localMaterializer = LocalPageMaterializer(
        LocalPageCache(File(getApplication<android.app.Application>().cacheDir, "local-pages")),
        DefaultLocalPageSource(getApplication<android.app.Application>().contentResolver, localArchiveAccess),
    )
    val provider: PageProvider = LocalFirstPageProvider(
        source = offlineProvider,
        localRepository = { _local.value ?: _local.filterNotNull().first() },
        materializer = localMaterializer,
    )

    /**
     * The source's own chapter order for a downloaded chapter, so opening chapter 5 can still reach
     * the never-downloaded chapter 6 instead of declaring the series finished.
     *
     * Cached per comic: one detail response covers every chapter, and chaining chapters must not pay
     * a network round trip at each boundary. A failure simply returns null, which lets the
     * downloaded order stand in.
     */
    private suspend fun sourceChapterNeighbors(ref: ChapterRef.Remote): ChapterNeighbors? {
        val chapterKey = ref.key
        val comicKey = chapterKey.comicKey
        val now = System.currentTimeMillis()
        val chapters = sourceChapterListCache[comicKey]
            ?.takeIf { (cachedAt, _) -> now - cachedAt < CHAPTER_LIST_CACHE_MILLIS }
            ?.second
            ?: ((catalog.detail(comicKey) as? SourceOutcome.Success)?.value?.chapters ?: return null)
                .also { sourceChapterListCache[comicKey] = now to it }
        val scoped = ref.group?.let { group -> chapters.filter { it.group == group } } ?: chapters
        val position = scoped.indexOfFirst { it.key == chapterKey }
        if (position < 0) return null
        return ChapterNeighbors(
            previousChapter = scoped.getOrNull(position - 1)?.let { ChapterRef.Remote(it.key, it.group) },
            nextChapter = scoped.getOrNull(position + 1)?.let { ChapterRef.Remote(it.key, it.group) },
        )
    }
    val decoderFactory: (DecodeStrategy) -> PageImageDecoder = { strategy ->
        val decoder = when (strategy) {
            DecodeStrategy.Sampled -> SampledPageImageDecoder()
            DecodeStrategy.Region -> RegionPageImageDecoder()
        }
        val pipelineDecoder = dev.veneranative.core.image.decode.PipelinePageImageDecoder(
            imagePipeline,
            decoder,
            onDecodeFailure = { request, failure ->
                if (request.sourceId?.value.equals("jm", ignoreCase = true)) {
                    val region = request.region
                    val codeLocation = failure.stackTrace
                        .asSequence()
                        .filter {
                            it.className.startsWith("dev.veneranative.") ||
                                it.className.startsWith("android.graphics.")
                        }
                        .take(6)
                        .joinToString(">") { frame ->
                            "${frame.className.substringAfterLast('.')}.${frame.methodName}:${frame.lineNumber}"
                        }.ifBlank { "unavailable" }
                    host.logJmReaderDecodeFailure(
                        "page=${request.pageIndex} strategy=${strategy.name} " +
                            "target=${request.targetWidthPx}x${request.targetHeightPx} " +
                            "region=${region?.let { "${it.leftPx},${it.topPx},${it.widthPx}x${it.heightPx}" } ?: "whole"} " +
                            "bands=${request.reverseHorizontalBands ?: 0} " +
                            "errorType=${failure.javaClass.simpleName} " +
                            "causeType=${failure.cause?.javaClass?.simpleName ?: "none"} " +
                            "codeLocation=$codeLocation",
                    )
                }
            },
        )
        // Cache completed pages/tiles outside the transform. Intermediate strips are recycled
        // immediately after drawing and must never be retained by the bitmap cache.
        CachingPageImageDecoder(pipelineDecoder, imageCache)
    }
    private val _history = kotlinx.coroutines.flow.MutableStateFlow<HistoryRepository?>(null)
    val history: kotlinx.coroutines.flow.StateFlow<HistoryRepository?> = _history
    private val _collection = kotlinx.coroutines.flow.MutableStateFlow<CollectionRepository?>(null)
    val collection: kotlinx.coroutines.flow.StateFlow<CollectionRepository?> = _collection
    private val _download = kotlinx.coroutines.flow.MutableStateFlow<DownloadRepository?>(null)
    private val _local = kotlinx.coroutines.flow.MutableStateFlow<LocalComicRepository?>(null)
    private val _searchHistory = kotlinx.coroutines.flow.MutableStateFlow<SearchHistoryRepository?>(null)
    private val _screenPreferences = kotlinx.coroutines.flow.MutableStateFlow<ScreenPreferenceRepository?>(null)
    private val _splitPreference = kotlinx.coroutines.flow.MutableStateFlow<SplitPreferenceRepository?>(null)
    val local: kotlinx.coroutines.flow.StateFlow<LocalComicRepository?> = _local
    val searchHistory: kotlinx.coroutines.flow.StateFlow<SearchHistoryRepository?> = _searchHistory
    val screenPreferences: kotlinx.coroutines.flow.StateFlow<ScreenPreferenceRepository?> = _screenPreferences
    val splitPreference: kotlinx.coroutines.flow.StateFlow<SplitPreferenceRepository?> = _splitPreference
    private val _backupFactory = kotlinx.coroutines.flow.MutableStateFlow<GitHubBackupGatewayFactory?>(null)
    val backupFactory: kotlinx.coroutines.flow.StateFlow<GitHubBackupGatewayFactory?> = _backupFactory
    val download: kotlinx.coroutines.flow.StateFlow<DownloadRepository?> = _download
    init {
        scope.launch {
            val db = VeneraDatabaseFactory.get(getApplication())
            _backupFactory.value = GitHubBackupGatewayFactory(
                getApplication(), httpClient, RoomBackupRepository(db), GitHubBackupConfiguration.CLIENT_ID,
            )
            val repository = DefaultHistoryRepository(db)
            _searchHistory.value = DefaultSearchHistoryRepository(db.searchHistoryDao())
            val screenPreferences = DefaultScreenPreferenceRepository(db.screenPreferenceDao())
            _screenPreferences.value = screenPreferences
            _splitPreference.value = DefaultSplitPreferenceRepository(screenPreferences)
            progressTracker.set(ReadingProgressTracker(repository, scope, clock = { System.currentTimeMillis() }))
            _history.value = repository
            // The shelf asks installed sources for chapter snapshots; the assembly layer is the only
            // place that can see both the repository and the catalog.
            _collection.value = DefaultCollectionRepository(db, ComicCatalogChapterProbe(catalog), covers = host.savedComicCovers, maintenanceScope = scope)
            _download.value = DownloadEnvironment.get(getApplication()).repository()
            _local.value = DefaultLocalComicRepository(
                db, AndroidSafTreeAccess(getApplication()), archiveAccess = localArchiveAccess,
            )
        }
    }
    private val foreground = kotlinx.coroutines.flow.MutableStateFlow(false)
    init {
        scope.launch {
            kotlinx.coroutines.flow.combine(foreground, collection) { active, repository -> if (active) repository else null }
                .collectLatest { repository ->
                    if (repository != null) try { repository.maintainWhileActive() }
                    catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                    catch (_: Exception) { android.util.Log.w("VeneraLibrary", "Retained comic refresh unavailable") }
                }
        }
    }
    fun setForeground(active: Boolean) { foreground.value = active }
    fun flushProgress() { scope.launch { runCatching { progressTracker.get()?.flush() } } }
    override fun onCleared() {
        scope.launch(NonCancellable) {
            try { progressTracker.get()?.flush() } finally {
                // Only what this graph owns is closed here. The HTTP client, disk cache, image
                // pipeline and image loader belong to the process: shutting them down when a screen
                // goes away would stop downloads that are still running behind it.
                runtime.close()
                imageCache.clear()
                scope.cancel()
            }
        }
    }
}

/** Chapter lists change slowly; five minutes keeps chapter chaining snappy without going stale. */
private const val CHAPTER_LIST_CACHE_MILLIS = 5 * 60 * 1000L
