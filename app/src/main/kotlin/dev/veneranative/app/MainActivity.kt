package dev.veneranative.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import dev.veneranative.core.designsystem.VeneraNativeTheme
import dev.veneranative.core.model.ChapterKey
import dev.veneranative.core.model.ComicKey
import dev.veneranative.core.model.RemoteChapterId
import dev.veneranative.core.model.RemoteComicId
import dev.veneranative.core.model.SourceId
import dev.veneranative.data.source.DefaultSourceRepository
import dev.veneranative.data.source.LocalFileScriptFetcher
import dev.veneranative.data.source.SourcePackageStore
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
import dev.veneranative.feature.sources.SourcesRoute
import dev.veneranative.source.api.SourceMetadataReader
import dev.veneranative.source.api.SourceMetadataResult
import dev.veneranative.source.engine.AndroidJavaScriptRuntime
import java.io.File

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            VeneraNativeTheme {
                // Assembly layer: features receive their dependencies from here, so no feature has
                // to know about storage, the engine or another feature. Root navigation is still
                // local state; type-safe routes arrive with the first slice that needs arguments.
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
                val sourceRepository = remember(context) {
                    DefaultSourceRepository(
                        store = SourcePackageStore(File(context.filesDir, "sources")),
                        runtime = AndroidJavaScriptRuntime(context = context),
                        metadataReader = UnavailableMetadataReader,
                        fetcher = LocalFileScriptFetcher(),
                    )
                }

                var screen by rememberSaveable { mutableStateOf(AppScreen.Home) }
                val demoChapter = remember {
                    ChapterKey(
                        comicKey = ComicKey(SourceId("demo"), RemoteComicId("demo-comic")),
                        remoteId = RemoteChapterId("demo-chapter"),
                    )
                }

                when (screen) {
                    AppScreen.Home -> HomeRoute(
                        onOpenReader = { screen = AppScreen.Reader },
                        onOpenSources = { screen = AppScreen.Sources },
                    )

                    AppScreen.Sources -> SourcesRoute(repository = sourceRepository)

                    AppScreen.Reader -> ReaderRoute(
                        chapter = demoChapter,
                        provider = provider,
                        onBack = { screen = AppScreen.Home },
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

private enum class AppScreen {
    Home,
    Sources,
    Reader,
}

private fun decoderFor(strategy: DecodeStrategy, cache: PageImageCache): PageImageDecoder {
    val decoder: PageImageDecoder = when (strategy) {
        DecodeStrategy.Sampled -> SampledPageImageDecoder()
        DecodeStrategy.Region -> RegionPageImageDecoder()
    }
    return CachingPageImageDecoder(delegate = decoder, cache = cache)
}

/**
 * Placeholder until the engine work can read a script's declared metadata.
 *
 * `key`, `name` and `version` only exist after the script has been executed, so this reports the
 * honest answer — sources are unavailable — instead of guessing them from the script text. It is an
 * assembly-layer decision and is replaced by the engine-backed reader in S1-08.
 */
private object UnavailableMetadataReader : SourceMetadataReader {
    override suspend fun read(script: String): SourceMetadataResult =
        SourceMetadataResult.EngineUnavailable("Source metadata reading is not implemented yet.")
}
