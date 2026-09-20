package dev.veneranative.source.api

import dev.veneranative.core.model.Chapter
import dev.veneranative.core.model.ChapterKey
import dev.veneranative.core.model.Comic
import dev.veneranative.core.model.ComicDetail
import dev.veneranative.core.model.ComicKey
import dev.veneranative.core.model.ExploreItem
import dev.veneranative.core.model.FilterSelection
import dev.veneranative.core.model.PageCursor
import dev.veneranative.core.model.PagedResult
import dev.veneranative.core.model.SourceCapabilities
import dev.veneranative.core.model.SourceCapability
import dev.veneranative.core.model.SourceId
import dev.veneranative.core.model.SourcePage

/**
 * The five capabilities Feature and Data layers are allowed to use.
 *
 * Everything a caller needs is typed: no method names, no JSON, no script. The implementation maps
 * these calls onto source invocations, so this contract survives an engine replacement (ADR-0008).
 *
 * Failures are returned as [SourceOutcome.Failure] rather than thrown, because a failing source is
 * an expected product state, not an exceptional one. A capability the source does not implement is
 * reported as [SourceRuntimeError.UnsupportedCapability] and must not be presented as a source-wide
 * failure (ADR-0007).
 */
interface SourceCore {

    suspend fun capabilities(sourceId: SourceId): SourceOutcome<SourceCapabilities>

    /** One explore page. The requested page must exist in the capabilities the source declared. */
    suspend fun explore(request: ExploreRequest): SourceOutcome<PagedResult<ExploreItem>>

    suspend fun search(request: SearchRequest): SourceOutcome<PagedResult<Comic>>

    suspend fun detail(comicKey: ComicKey): SourceOutcome<ComicDetail>

    /** Chapters of a comic. An implementation may answer this from a cached detail response. */
    suspend fun chapters(comicKey: ComicKey): SourceOutcome<List<Chapter>>

    /**
     * Page references of a chapter.
     *
     * These are URLs, not sized descriptors: sources do not report page dimensions, so the image
     * pipeline resolves them later. Nothing here carries pixels either way.
     */
    suspend fun pages(chapterKey: ChapterKey): SourceOutcome<List<SourcePage>>
}

/** Result of a capability call. */
sealed interface SourceOutcome<out T> {
    data class Success<T>(val value: T) : SourceOutcome<T>

    data class Failure(val error: SourceRuntimeError) : SourceOutcome<Nothing>

    companion object {
        /** The source did not declare this capability; callers must degrade, not retry. */
        fun unsupported(capability: SourceCapability): Failure =
            Failure(SourceRuntimeError.UnsupportedCapability(capability))
    }
}

data class ExploreRequest(
    val sourceId: SourceId,
    val pageKey: String,
    val cursor: PageCursor? = null,
    val filters: FilterSelection = FilterSelection.Empty,
) {
    init {
        require(pageKey.isNotBlank()) { "explore page key must not be blank" }
    }
}

data class SearchRequest(
    val sourceId: SourceId,
    val keyword: String,
    val cursor: PageCursor? = null,
    val filters: FilterSelection = FilterSelection.Empty,
) {
    init {
        require(keyword.isNotBlank()) { "search keyword must not be blank" }
    }
}
