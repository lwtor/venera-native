package dev.veneranative.feature.reader

import dev.veneranative.core.model.ChapterKey
import dev.veneranative.core.model.ComicPage
import kotlinx.coroutines.delay

/**
 * Deterministic in-memory provider used by previews, the demo entry point and tests.
 *
 * It only produces page descriptors: no image is decoded and no bitmap is held, which keeps the
 * prototype honest about the S0-05 constraint. A real decoding pipeline is S0-06 work.
 */
class FakePageProvider(
    private val pageCount: Int = DEFAULT_PAGE_COUNT,
    private val loadDelayMillis: Long = 0L,
) : PageProvider {

    private val prefetched = mutableListOf<Int>()

    /** Indices passed to [prefetch], in call order. Exposed so tests can assert prefetching. */
    val prefetchedPages: List<Int> get() = prefetched.toList()

    override suspend fun loadChapter(chapter: ChapterKey): ChapterContent {
        if (loadDelayMillis > 0) delay(loadDelayMillis)
        val pages = List(pageCount) { index ->
            ComicPage(
                index = index,
                imageRef = "fake://${chapter.value}/$index",
                widthPx = PAGE_WIDTH_PX,
                // Every fourth page is a taller one so scrolling behaviour is not uniform.
                heightPx = if (index % 4 == 3) TALL_PAGE_HEIGHT_PX else PAGE_HEIGHT_PX,
            )
        }
        return ChapterContent(title = "Chapter ${chapter.value}", pages = pages)
    }

    override suspend fun prefetch(page: ComicPage) {
        prefetched += page.index
    }

    private companion object {
        const val DEFAULT_PAGE_COUNT = 12
        const val PAGE_WIDTH_PX = 1080
        const val PAGE_HEIGHT_PX = 1440
        const val TALL_PAGE_HEIGHT_PX = 1620
    }
}
