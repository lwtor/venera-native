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

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            VeneraNativeTheme {
                // Stage 0 assembly: the reader is wired to an in-memory provider so the prototype
                // can be opened without a real source. When the S0-06 fixtures have been generated
                // into the assets (see tools/test-images) the same reader opens them with a real
                // decoding pipeline instead. Root navigation arrives with the first feature slice
                // that needs more than one destination.
                val context = LocalContext.current.applicationContext
                val fixturesAvailable = remember { AssetFixturePageProvider.hasFixtures(context) }
                val imageCache = remember { PageImageCache(DEFAULT_IMAGE_CACHE_BYTES) }
                val provider: PageProvider = remember {
                    if (fixturesAvailable) AssetFixturePageProvider(context) else FakePageProvider()
                }
                val decoderFactory: ((DecodeStrategy) -> PageImageDecoder)? = remember(imageCache, fixturesAvailable) {
                    if (fixturesAvailable) {
                        { strategy -> decoderFor(strategy, imageCache) }
                    } else {
                        null
                    }
                }

                var readerOpen by rememberSaveable { mutableStateOf(false) }
                val demoChapter = remember {
                    ChapterKey(
                        comicKey = ComicKey(SourceId("demo"), RemoteComicId("demo-comic")),
                        remoteId = RemoteChapterId("demo-chapter"),
                    )
                }

                if (readerOpen) {
                    ReaderRoute(
                        chapter = demoChapter,
                        provider = provider,
                        onBack = { readerOpen = false },
                        decoderFactory = decoderFactory,
                    )
                } else {
                    HomeRoute(onOpenReader = { readerOpen = true })
                }
            }
        }
    }

    private companion object {
        const val DEFAULT_IMAGE_CACHE_BYTES = 64L * 1024 * 1024
    }
}

private fun decoderFor(strategy: DecodeStrategy, cache: PageImageCache): PageImageDecoder {
    val decoder: PageImageDecoder = when (strategy) {
        DecodeStrategy.Sampled -> SampledPageImageDecoder()
        DecodeStrategy.Region -> RegionPageImageDecoder()
    }
    return CachingPageImageDecoder(delegate = decoder, cache = cache)
}
