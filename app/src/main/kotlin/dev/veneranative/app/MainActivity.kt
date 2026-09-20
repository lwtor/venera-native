package dev.veneranative.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.veneranative.core.designsystem.VeneraNativeTheme
import dev.veneranative.core.model.ChapterKey
import dev.veneranative.core.model.ComicKey
import dev.veneranative.core.model.RemoteChapterId
import dev.veneranative.core.model.RemoteComicId
import dev.veneranative.core.model.SourceId
import dev.veneranative.core.navigation.AppRoute
import dev.veneranative.core.navigation.decodeAppRoute
import dev.veneranative.core.navigation.encode
import dev.veneranative.data.comic.DefaultComicCatalog
import dev.veneranative.data.source.DefaultSourceRepository
import dev.veneranative.data.source.LocalFileScriptFetcher
import dev.veneranative.data.source.SourcePackageStore
import dev.veneranative.feature.explore.ExploreRoute
import dev.veneranative.feature.home.HomeRoute
import dev.veneranative.feature.reader.AssetFixturePageProvider
import dev.veneranative.feature.reader.FakePageProvider
import dev.veneranative.feature.reader.PageProvider
import dev.veneranative.feature.reader.ReaderRoute
import dev.veneranative.feature.reader.image.CachingPageImageDecoder
import dev.veneranative.feature.reader.image.DecodeStrategy
import dev.veneranative.feature.reader.image.PageImageCache
import dev.veneranative.feature.reader.image.PageImageDecoder
import dev.veneranative.feature.reader.image.RegionPageImageDecoder
import dev.veneranative.feature.reader.image.SampledPageImageDecoder
import dev.veneranative.feature.search.SearchRoute
import dev.veneranative.feature.sources.SourcesRoute
import dev.veneranative.source.core.EngineSourceCore
import dev.veneranative.source.engine.QuickJsMetadataReader
import dev.veneranative.source.engine.QuickJsRuntime
import dev.veneranative.source.network.SourceNetworkExecutor
import dev.veneranative.source.network.SourceNetworkHostApi
import java.io.File
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            VeneraNativeTheme {
                // Assembly layer: features receive their dependencies from here, so no feature has
                // to know about storage, the engine or another feature.
                val context = LocalContext.current.applicationContext
                val fixturesAvailable = remember { AssetFixturePageProvider.hasFixtures(context) }
                val imageCache = remember { PageImageCache(DEFAULT_IMAGE_CACHE_BYTES) }
                val provider: PageProvider = remember {
                    if (fixturesAvailable) AssetFixturePageProvider(context) else FakePageProvider()
                }
                val decoderFactory: ((DecodeStrategy) -> PageImageDecoder)? =
                    remember(imageCache, fixturesAvailable) {
                        if (fixturesAvailable) {
                            { strategy -> decoderFor(strategy, imageCache) }
                        } else {
                            null
                        }
                    }

                // One runtime serves the whole app: sources are loaded into it, and both the
                // repository and the catalog above it talk to the same loaded instances.
                val runtime = remember {
                    QuickJsRuntime(
                        hostApi = SourceNetworkHostApi(SourceNetworkExecutor()),
                        appLocale = Locale.getDefault().toString(),
                    )
                }
                val sourceRepository = remember(context) {
                    DefaultSourceRepository(
                        store = SourcePackageStore(File(context.filesDir, "sources")),
                        runtime = runtime,
                        metadataReader = QuickJsMetadataReader(),
                        fetcher = LocalFileScriptFetcher(),
                    )
                }
                val catalog = remember(sourceRepository) {
                    DefaultComicCatalog(sources = sourceRepository, core = EngineSourceCore(runtime))
                }

                var route by rememberSaveable(stateSaver = AppRouteSaver) {
                    mutableStateOf<AppRoute>(AppRoute.Home)
                }
                val demoChapter = remember {
                    ChapterKey(
                        comicKey = ComicKey(SourceId("demo"), RemoteComicId("demo-comic")),
                        remoteId = RemoteChapterId("demo-chapter"),
                    )
                }

                when (val current = route) {
                    AppRoute.Home -> HomeRoute(
                        onOpenReader = { route = AppRoute.Reader(demoChapter) },
                        onOpenSources = { route = AppRoute.Sources },
                        onOpenExplore = { route = AppRoute.Explore(null) },
                        onOpenSearch = { route = AppRoute.Search(null) },
                    )

                    AppRoute.Sources -> SourcesRoute(
                        repository = sourceRepository,
                        onBack = { route = AppRoute.Home },
                    )

                    is AppRoute.Explore -> ExploreRoute(
                        catalog = catalog,
                        onOpenComic = { route = AppRoute.ComicDetails(it) },
                        onBack = { route = AppRoute.Home },
                    )

                    is AppRoute.Search -> SearchRoute(
                        catalog = catalog,
                        onOpenComic = { route = AppRoute.ComicDetails(it) },
                        onBack = { route = AppRoute.Home },
                    )

                    is AppRoute.ComicDetails -> ComicDetailsPlaceholder(
                        comicKey = current.comicKey,
                        onBack = { route = AppRoute.Home },
                    )

                    is AppRoute.Reader -> ReaderRoute(
                        chapter = current.chapter,
                        provider = provider,
                        onBack = { route = AppRoute.Home },
                        decoderFactory = decoderFactory,
                    )
                }
            }
        }
    }

    private companion object {
        const val DEFAULT_IMAGE_CACHE_BYTES = 64L * 1024 * 1024
    }
}

/** Keeps the current route across process death; the encoding itself lives in `:core:navigation`. */
private val AppRouteSaver: Saver<AppRoute, String> = Saver(
    save = { it.encode() },
    restore = { decodeAppRoute(it) ?: AppRoute.Home },
)

/**
 * Stand-in for the details screen.
 *
 * The route is typed and wired all the way here already — a search or explore result hands over a
 * `ComicKey`, not a string — and S1-04 replaces this with the real screen. Until then it names the
 * comic it would show instead of pretending to be finished.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ComicDetailsPlaceholder(
    comicKey: ComicKey,
    onBack: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Comic") },
                navigationIcon = { TextButton(onClick = onBack) { Text("Back") } },
            )
        },
    ) { contentPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding)
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(text = comicKey.remoteId.value, style = MaterialTheme.typography.titleMedium)
            Text(text = "from ${comicKey.sourceId.value}", style = MaterialTheme.typography.bodySmall)
            Text(
                text = "Comic details arrive with S1-04.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

private fun decoderFor(strategy: DecodeStrategy, cache: PageImageCache): PageImageDecoder {
    val decoder: PageImageDecoder = when (strategy) {
        DecodeStrategy.Sampled -> SampledPageImageDecoder()
        DecodeStrategy.Region -> RegionPageImageDecoder()
    }
    return CachingPageImageDecoder(delegate = decoder, cache = cache)
}
