package dev.veneranative.source.api

import dev.veneranative.core.model.Chapter
import dev.veneranative.core.model.ChapterKey
import dev.veneranative.core.model.Comic
import dev.veneranative.core.model.ComicDetail
import dev.veneranative.core.model.ComicKey
import dev.veneranative.core.model.ComicPage
import dev.veneranative.core.model.ExploreItem
import dev.veneranative.core.model.PageCursor
import dev.veneranative.core.model.PagedResult
import dev.veneranative.core.model.RemoteComicId
import dev.veneranative.core.model.SourceCapabilities
import dev.veneranative.core.model.SourceCapability
import dev.veneranative.core.model.SourceId
import dev.veneranative.core.model.chaptersOf
import kotlin.math.ceil

/**
 * Reference implementation of [SourceCore] used to pin the contract semantics.
 *
 * It is deliberately partial: it supports search, detail and chapters, but not explore or pages, so
 * tests can assert that a missing capability surfaces as `UnsupportedCapability` instead of as a
 * source failure. It also fails on a chosen keyword, so tests can assert that failures travel as
 * data rather than as exceptions.
 *
 * A real engine-backed implementation must satisfy the same expectations; when it lands it should
 * reuse these assertions rather than inventing new ones.
 */
class FakeSourceCore(
    private val sourceId: SourceId = SourceId("fake-source"),
    private val comics: List<Comic> = defaultComics(),
    private val pageSize: Int = DEFAULT_PAGE_SIZE,
    private val chapterTitles: Map<String, String> = linkedMapOf(
        "chapter-2" to "Chapter 2",
        "chapter-1" to "Chapter 1",
    ),
    val declaredCapabilities: SourceCapabilities = SourceCapabilities(
        supported = setOf(
            SourceCapability.SEARCH,
            SourceCapability.DETAIL,
            SourceCapability.CHAPTERS,
        ),
    ),
) : SourceCore {

    /** Keywords that must fail; used to prove failures are values, not exceptions. */
    val failingKeywords: Set<String> = setOf(FAILING_KEYWORD)

    var searchCalls: Int = 0
        private set

    override suspend fun capabilities(sourceId: SourceId): SourceOutcome<SourceCapabilities> =
        if (sourceId == this.sourceId) {
            SourceOutcome.Success(declaredCapabilities)
        } else {
            SourceOutcome.Failure(SourceRuntimeError.SourceNotLoaded(sourceId))
        }

    override suspend fun explore(request: ExploreRequest): SourceOutcome<PagedResult<ExploreItem>> =
        if (declaredCapabilities.supports(SourceCapability.EXPLORE)) {
            SourceOutcome.Success(PagedResult(items = listOf(ExploreItem.Comics(comics))))
        } else {
            SourceOutcome.unsupported(SourceCapability.EXPLORE)
        }

    override suspend fun search(request: SearchRequest): SourceOutcome<PagedResult<Comic>> {
        if (request.sourceId != sourceId) {
            return SourceOutcome.Failure(SourceRuntimeError.SourceNotLoaded(request.sourceId))
        }
        if (!declaredCapabilities.supports(SourceCapability.SEARCH)) {
            return SourceOutcome.unsupported(SourceCapability.SEARCH)
        }
        if (request.keyword in failingKeywords) {
            return SourceOutcome.Failure(SourceRuntimeError.Timeout(timeoutMillis = DEFAULT_TIMEOUT_MILLIS))
        }

        searchCalls++
        val pageNumber = (request.cursor as? PageCursor.Page)?.number ?: FIRST_PAGE
        val from = (pageNumber - 1) * pageSize
        val slice = comics.drop(from).take(pageSize)
        val totalPages = ceil(comics.size.toDouble() / pageSize.toDouble()).toInt()
        return SourceOutcome.Success(PagedResult.page(slice, pageNumber, totalPages))
    }

    override suspend fun detail(comicKey: ComicKey): SourceOutcome<ComicDetail> {
        if (comicKey.sourceId != sourceId) {
            return SourceOutcome.Failure(SourceRuntimeError.SourceNotLoaded(comicKey.sourceId))
        }
        if (!declaredCapabilities.supports(SourceCapability.DETAIL)) {
            return SourceOutcome.unsupported(SourceCapability.DETAIL)
        }
        val comic = comics.firstOrNull { it.key == comicKey }
            ?: return SourceOutcome.Failure(SourceRuntimeError.InvalidCall("Unknown comic."))
        return SourceOutcome.Success(
            ComicDetail(
                comic = comic,
                description = "A fixture comic.",
                chapters = chaptersOf(comicKey, chapterTitles),
            ),
        )
    }

    override suspend fun chapters(comicKey: ComicKey): SourceOutcome<List<Chapter>> {
        if (!declaredCapabilities.supports(SourceCapability.CHAPTERS)) {
            return SourceOutcome.unsupported(SourceCapability.CHAPTERS)
        }
        return when (val outcome = detail(comicKey)) {
            is SourceOutcome.Success -> SourceOutcome.Success(outcome.value.chapters)
            is SourceOutcome.Failure -> outcome
        }
    }

    override suspend fun pages(chapterKey: ChapterKey): SourceOutcome<List<ComicPage>> =
        if (declaredCapabilities.supports(SourceCapability.PAGES)) {
            SourceOutcome.Success(
                List(FIXTURE_PAGE_COUNT) { index ->
                    ComicPage(
                        index = index,
                        imageRef = "fake://${chapterKey.remoteId.value}/$index",
                        widthPx = 1080,
                        heightPx = 1440,
                    )
                },
            )
        } else {
            SourceOutcome.unsupported(SourceCapability.PAGES)
        }

    private companion object {
        const val DEFAULT_PAGE_SIZE = 2
        const val FIRST_PAGE = 1
        const val FIXTURE_PAGE_COUNT = 3
        const val DEFAULT_TIMEOUT_MILLIS = 10_000L
        const val FAILING_KEYWORD = "boom"

        fun defaultComics(): List<Comic> = (1..5).map { number ->
            Comic(
                key = ComicKey(SourceId("fake-source"), RemoteComicId("comic-$number")),
                title = "Comic $number",
            )
        }
    }
}
