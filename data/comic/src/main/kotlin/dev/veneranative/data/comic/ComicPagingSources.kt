package dev.veneranative.data.comic

import androidx.paging.PagingSource
import androidx.paging.PagingState
import dev.veneranative.core.model.Comic
import dev.veneranative.core.model.ExploreItem
import dev.veneranative.source.api.ExploreRequest
import dev.veneranative.source.api.SearchRequest
import dev.veneranative.source.api.SourceCore
import dev.veneranative.source.api.SourceOutcome

/**
 * Paging 3 over a source's own pagination.
 *
 * Only forward paging is offered: `prevKey` is always null. A cursor source cannot walk backwards at
 * all, and a page-numbered source would need a second request shape for it — the lists this feeds
 * are browsed forward, so a back key would be a promise the sources cannot keep.
 */
internal class SearchPagingSource(
    private val core: SourceCore,
    private val request: SearchRequest,
) : PagingSource<PageKey, Comic>() {

    override fun getRefreshKey(state: PagingState<PageKey, Comic>): PageKey = PageKey.Start

    override suspend fun load(params: LoadParams<PageKey>): LoadResult<PageKey, Comic> =
        when (val outcome = core.search(request.copy(cursor = params.key.cursor()))) {
            is SourceOutcome.Success ->
                LoadResult.Page(
                    data = outcome.value.items,
                    prevKey = null,
                    nextKey = outcome.value.next?.let(PageKey::At),
                )

            is SourceOutcome.Failure -> LoadResult.Error(SourceLoadException(outcome.error))
        }
}

internal class ExplorePagingSource(
    private val core: SourceCore,
    private val request: ExploreRequest,
) : PagingSource<PageKey, ExploreItem>() {

    override fun getRefreshKey(state: PagingState<PageKey, ExploreItem>): PageKey = PageKey.Start

    override suspend fun load(params: LoadParams<PageKey>): LoadResult<PageKey, ExploreItem> =
        when (val outcome = core.explore(request.copy(cursor = params.key.cursor()))) {
            is SourceOutcome.Success ->
                LoadResult.Page(
                    data = outcome.value.items,
                    prevKey = null,
                    nextKey = outcome.value.next?.let(PageKey::At),
                )

            is SourceOutcome.Failure -> LoadResult.Error(SourceLoadException(outcome.error))
        }
}

/**
 * The cursor to ask for.
 *
 * `null` means "the source decides what the first page is": a page-numbered source counts from 1 and
 * a cursor source receives a null token, which is exactly what upstream expects of a first call
 * (ADR-0007 §2.2). The adapter knows which of the two a source is; neither Paging nor the UI has to.
 */
private fun PageKey?.cursor() = (this as? PageKey.At)?.cursor
