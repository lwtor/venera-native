package dev.veneranative.data.comic

import androidx.paging.PagingSource
import dev.veneranative.core.model.ChapterKey
import dev.veneranative.core.model.Chapter
import dev.veneranative.core.model.Comic
import dev.veneranative.core.model.ComicDetail
import dev.veneranative.core.model.ComicKey
import dev.veneranative.core.model.ExploreItem
import dev.veneranative.core.model.ImageSize
import dev.veneranative.core.model.InstalledSource
import dev.veneranative.core.model.PageImageSizer
import dev.veneranative.core.model.RemoteChapterId
import dev.veneranative.core.model.RemoteComicId
import dev.veneranative.core.model.SourceCapabilities
import dev.veneranative.core.model.SourceCapability
import dev.veneranative.core.model.SourceId
import dev.veneranative.core.model.SourcePage
import dev.veneranative.source.api.ExploreRequest
import dev.veneranative.source.api.SearchRequest
import dev.veneranative.source.api.SourceOutcome
import dev.veneranative.source.api.SourceRuntimeError
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * How a source's chapter becomes pages the reader can lay out.
 *
 * The interesting part is the failure behaviour: a source answers with URLs, sizes come from the
 * image pipeline, and one unresolvable URL is the normal case rather than an exception. The
 * assertions below pin that failed pages retain their positions and can be resolved independently.
 */
class SourcePageProviderTest {

    private val sourceId = SourceId("source-a")

    private val chapter = ChapterKey(
        comicKey = ComicKey(sourceId, RemoteComicId("comic-1")),
        remoteId = RemoteChapterId("chapter-1"),
    )

    @Test
    fun `pages are measured and keep the order the source listed them in`() = runTest {
        val provider = SourcePageProvider(
            catalog = FakeCatalog(
                pages = listOf(
                    SourcePage(index = 0, imageRef = "https://img/0"),
                    SourcePage(index = 1, imageRef = "https://img/1"),
                    SourcePage(index = 2, imageRef = "https://img/2"),
                ),
            ),
            sizer = FixedSizer(
                "https://img/0" to ImageSize(800, 1200),
                "https://img/1" to ImageSize(900, 1300),
                "https://img/2" to ImageSize(1000, 1400),
            ),
        )

        val content = provider.loadChapter(chapter)

        assertEquals(
            listOf("https://img/0", "https://img/1", "https://img/2"),
            content.pages.map { it.imageRef },
        )
        assertEquals(listOf(0, 1, 2), content.pages.map { it.index })
        assertEquals(dev.veneranative.core.model.PageSizeState.Pending, content.pages.first().sizeState)
        assertEquals(1200, provider.resolve(content.pages.first()).heightPx)
    }

    @Test
    fun `a broken page retains its stable position for retry`() = runTest {
        val provider = SourcePageProvider(
            catalog = FakeCatalog(
                pages = listOf(
                    SourcePage(index = 0, imageRef = "https://img/0"),
                    SourcePage(index = 1, imageRef = "https://img/broken"),
                    SourcePage(index = 2, imageRef = "https://img/2"),
                ),
            ),
            sizer = FixedSizer(
                "https://img/0" to ImageSize(800, 1200),
                "https://img/2" to ImageSize(1000, 1400),
            ),
        )

        val content = provider.loadChapter(chapter)

        assertEquals(listOf("https://img/0", "https://img/broken", "https://img/2"), content.pages.map { it.imageRef })
        // Failed images retain their position.
        assertEquals(listOf(0, 1, 2), content.pages.map { it.index })
    }

    @Test
    fun `unresolvable images remain retryable instead of becoming an empty chapter`() = runTest {
        val provider = SourcePageProvider(
            catalog = FakeCatalog(pages = listOf(SourcePage(index = 0, imageRef = "https://img/broken"))),
            sizer = FixedSizer(),
        )

        val content = provider.loadChapter(chapter)

        assertEquals(1, content.pages.size)
        assertTrue(runCatching { provider.resolve(content.pages.single()) }.isFailure)
    }

    @Test
    fun `the chapter title comes from the caller, not from the source response`() = runTest {
        val provider = SourcePageProvider(
            catalog = FakeCatalog(pages = listOf(SourcePage(0, "https://img/0"))),
            sizer = FixedSizer(),
            chapterTitle = { key -> "Chapter ${key.remoteId.value}" },
        )

        assertEquals("Chapter chapter-1", provider.loadChapter(chapter).title)
    }

    @Test
    fun `the next chapter follows the source declared order`() = runTest {
        val previous = Chapter(
            key = chapter.copy(remoteId = RemoteChapterId("chapter-0")),
            title = "Chapter 0",
            index = 0,
        )
        val next = Chapter(
            key = chapter.copy(remoteId = RemoteChapterId("chapter-2")),
            title = "Chapter 2",
            index = 2,
        )
        val catalog = object : ComicCatalog by FakeCatalog(pages = listOf(SourcePage(0, "https://img/0"))) {
            override suspend fun detail(comicKey: ComicKey): SourceOutcome<ComicDetail> =
                SourceOutcome.Success(
                    ComicDetail(
                        comic = Comic(comicKey, "Comic"),
                        chapters = listOf(previous, Chapter(chapter, "Chapter 1", 1), next),
                    ),
                )
        }
        val content = SourcePageProvider(catalog, FixedSizer()).loadChapter(chapter)

        assertEquals(next, content.nextChapter)
        assertEquals(previous, content.previousChapter)
    }

    @Test
    fun `chapter navigation stays inside the selected source version`() = runTest {
        val versionOne = listOf(
            Chapter(chapter.copy(remoteId = RemoteChapterId("v1-1")), "Chapter 1", 0, "Version 1"),
            Chapter(chapter.copy(remoteId = RemoteChapterId("v1-2")), "Chapter 2", 1, "Version 1"),
        )
        val versionTwo = listOf(
            Chapter(chapter.copy(remoteId = RemoteChapterId("v2-1")), "Chapter 1", 2, "Version 2"),
            Chapter(chapter.copy(remoteId = RemoteChapterId("v2-2")), "Chapter 2", 3, "Version 2"),
        )
        val catalog = object : ComicCatalog by FakeCatalog(pages = listOf(SourcePage(0, "https://img/0"))) {
            override suspend fun detail(comicKey: ComicKey): SourceOutcome<ComicDetail> =
                SourceOutcome.Success(ComicDetail(
                    comic = Comic(comicKey, "Comic"),
                    chapters = versionOne + versionTwo,
                ))
        }
        val provider = SourcePageProvider(catalog, FixedSizer())

        val middle = provider.loadChapter(dev.veneranative.core.model.ChapterRef.Remote(
            versionOne.first().key,
            group = "Version 1",
        ))
        val last = provider.loadChapter(dev.veneranative.core.model.ChapterRef.Remote(
            versionOne.last().key,
            group = "Version 1",
        ))

        assertEquals(versionOne.last(), middle.nextChapter)
        assertEquals(null, middle.previousChapter)
        assertEquals(null, last.nextChapter)
        assertEquals(versionOne.first(), last.previousChapter)
    }

    @Test
    fun `prefetched next chapter is reused and its first page is warmed`() = runTest {
        val nextKey = chapter.copy(remoteId = RemoteChapterId("chapter-2"))
        var pageLoads = 0
        val catalog = object : ComicCatalog by FakeCatalog(
            pages = listOf(SourcePage(index = 0, imageRef = "https://img/next")),
        ) {
            override suspend fun pages(chapterKey: ChapterKey): SourceOutcome<List<SourcePage>> {
                pageLoads++
                return SourceOutcome.Success(listOf(SourcePage(index = 0, imageRef = "https://img/next")))
            }

            override suspend fun detail(comicKey: ComicKey): SourceOutcome<ComicDetail> =
                SourceOutcome.Success(
                    ComicDetail(
                        comic = Comic(comicKey, "Comic"),
                        chapters = listOf(
                            Chapter(chapter, "Chapter 1", 0),
                            Chapter(nextKey, "Chapter 2", 1),
                        ),
                    ),
                )
        }
        val provider = SourcePageProvider(
            catalog,
            FixedSizer("https://img/next" to ImageSize(900, 1400)),
        )

        provider.prefetchChapter(dev.veneranative.core.model.ChapterRef.Remote(nextKey))
        val opened = provider.loadChapter(dev.veneranative.core.model.ChapterRef.Remote(nextKey))

        assertEquals(1, pageLoads)
        assertEquals(dev.veneranative.core.model.PageSizeState.Ready, opened.pages.single().sizeState)
        assertEquals("Chapter 2", opened.title)
    }

    @Test
    fun `a source failure travels as a domain error so the reader can retry`() = runTest {
        val failure = SourceRuntimeError.Timeout(timeoutMillis = 10_000)
        val provider = SourcePageProvider(
            catalog = FakeCatalog(error = failure),
            sizer = FixedSizer(),
        )

        val thrown = runCatching { provider.loadChapter(chapter) }.exceptionOrNull()

        assertEquals(failure, (thrown as? SourceLoadException)?.error)
    }

    @Test
    fun `chapter load retries briefly when the source is still unwinding a prior call`() = runTest {
        val references = listOf(SourcePage(index = 0, imageRef = "https://img/0"))
        var attempts = 0
        val catalog = object : ComicCatalog by FakeCatalog(pages = references) {
            override suspend fun pages(chapterKey: ChapterKey): SourceOutcome<List<SourcePage>> {
                attempts++
                return if (attempts == 1) {
                    SourceOutcome.Failure(SourceRuntimeError.Busy())
                } else {
                    SourceOutcome.Success(references)
                }
            }
        }
        val provider = SourcePageProvider(
            catalog = catalog,
            sizer = FixedSizer(),
            waitBeforeBusyRetry = {},
        )

        val content = provider.loadChapter(chapter)

        assertEquals(2, attempts)
        assertEquals(references.single().imageRef, content.pages.single().imageRef)
    }

    @Test
    fun `transient detail failure is retried so next chapter metadata is not silently lost`() = runTest {
        val next = Chapter(chapter.copy(remoteId = RemoteChapterId("chapter-2")), "Chapter 2", 1)
        var detailAttempts = 0
        val catalog = object : ComicCatalog by FakeCatalog(pages = listOf(SourcePage(0, "https://img/0"))) {
            override suspend fun detail(comicKey: ComicKey): SourceOutcome<ComicDetail> {
                detailAttempts++
                return if (detailAttempts == 1) {
                    SourceOutcome.Failure(SourceRuntimeError.Timeout(5_000))
                } else {
                    SourceOutcome.Success(ComicDetail(
                        comic = Comic(comicKey, "Comic"),
                        chapters = listOf(Chapter(chapter, "Chapter 1", 0), next),
                    ))
                }
            }
        }
        val provider = SourcePageProvider(catalog, FixedSizer(), waitBeforeBusyRetry = {})

        val content = provider.loadChapter(chapter)

        assertEquals(2, detailAttempts)
        assertEquals(next, content.nextChapter)
    }

    @Test
    fun `detail source failure is surfaced for reader retry instead of looking like series end`() = runTest {
        val failure = SourceRuntimeError.Timeout(5_000)
        var detailAttempts = 0
        val catalog = object : ComicCatalog by FakeCatalog(pages = listOf(SourcePage(0, "https://img/0"))) {
            override suspend fun detail(comicKey: ComicKey): SourceOutcome<ComicDetail> {
                detailAttempts++
                return SourceOutcome.Failure(failure)
            }
        }
        val provider = SourcePageProvider(catalog, FixedSizer(), waitBeforeBusyRetry = {})

        val thrown = runCatching { provider.loadChapter(chapter) }.exceptionOrNull()

        assertEquals(MAX_RETRIES + 1, detailAttempts)
        assertEquals(failure, (thrown as? SourceLoadException)?.error)
    }

    /** Only the sizes it was given; every other reference is "could not be resolved". */
    private class FixedSizer(
        vararg known: Pair<String, ImageSize>,
    ) : PageImageSizer {

        private val sizes: Map<String, ImageSize> = known.toMap()

        override suspend fun sizeOf(imageRef: String, sourceId: SourceId): ImageSize? = sizes[imageRef]
    }

    private class FakeCatalog(
        private val pages: List<SourcePage> = emptyList(),
        private val error: SourceRuntimeError? = null,
    ) : ComicCatalog {

        override suspend fun searchableSources(): List<InstalledSource> = emptyList()

        override suspend fun explorableSources(): List<InstalledSource> = emptyList()

        override suspend fun capabilities(sourceId: SourceId): SourceOutcome<SourceCapabilities> =
            SourceOutcome.Failure(SourceRuntimeError.SourceNotLoaded(sourceId))

        override suspend fun detail(comicKey: ComicKey): SourceOutcome<ComicDetail> =
            SourceOutcome.Failure(SourceRuntimeError.UnsupportedCapability(SourceCapability.DETAIL))

        override suspend fun enabledSource(sourceId: SourceId): InstalledSource? = null

        override fun explore(request: ExploreRequest): PagingSource<PageKey, ExploreItem> =
            error("not used by these tests")

        override fun search(request: SearchRequest): PagingSource<PageKey, Comic> =
            error("not used by these tests")

        override suspend fun pages(chapterKey: ChapterKey): SourceOutcome<List<SourcePage>> =
            error?.let { failure -> SourceOutcome.Failure(failure) } ?: SourceOutcome.Success(pages)
    }

    private companion object {
        const val MAX_RETRIES = 2
    }
}
