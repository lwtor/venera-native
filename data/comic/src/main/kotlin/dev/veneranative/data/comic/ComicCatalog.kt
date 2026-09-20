package dev.veneranative.data.comic

import androidx.paging.PagingSource
import dev.veneranative.core.model.Comic
import dev.veneranative.core.model.ExploreItem
import dev.veneranative.core.model.InstalledSource
import dev.veneranative.core.model.PageCursor
import dev.veneranative.core.model.SourceCapabilities
import dev.veneranative.core.model.SourceId
import dev.veneranative.source.api.ExploreRequest
import dev.veneranative.source.api.SearchRequest
import dev.veneranative.source.api.SourceOutcome
import dev.veneranative.source.api.SourceRuntimeError

/**
 * Comics reachable through the installed sources.
 *
 * It owns the two things features must not each reinvent: **which sources are usable** for a feature
 * (installed, enabled *and* declaring the capability) and **how a source's pagination becomes a
 * Paging 3 stream**. Everything else is passed through, including [SourceOutcome] as the error type:
 * those failures are already product states — unsupported capability, source not loaded, timeout —
 * rather than lower-layer diagnostics, so features map them to copy directly.
 */
interface ComicCatalog {

    /** Installed and enabled sources that declare `search`. */
    suspend fun searchableSources(): List<InstalledSource>

    /** Installed and enabled sources that declare at least one explore page. */
    suspend fun explorableSources(): List<InstalledSource>

    suspend fun capabilities(sourceId: SourceId): SourceOutcome<SourceCapabilities>

    /** A fresh paging source per request; a [PagingSource] is single-use. */
    fun explore(request: ExploreRequest): PagingSource<PageKey, ExploreItem>

    /** A fresh paging source per request. */
    fun search(request: SearchRequest): PagingSource<PageKey, Comic>
}

/**
 * The paging key.
 *
 * Paging 3 requires a non-null key, but "the first page" is genuinely not a cursor: a page-numbered
 * source is asked for page 1 and a cursor source is asked with a null token. [Start] expresses that
 * without inventing a cursor the source never returned.
 */
sealed interface PageKey {
    data object Start : PageKey

    data class At(val cursor: PageCursor) : PageKey
}

/**
 * Carries a source failure through Paging 3, which can only fail a load with a `Throwable`.
 *
 * The domain error stays reachable as [error] so the UI maps product copy instead of printing an
 * exception message.
 */
class SourceLoadException(val error: SourceRuntimeError) : Exception(error.message)
