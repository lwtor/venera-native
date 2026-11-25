package dev.veneranative.feature.search

import androidx.paging.PagingSource
import dev.veneranative.core.model.FilterSelection
import dev.veneranative.core.model.InstalledSource
import dev.veneranative.data.comic.ComicCatalog
import dev.veneranative.data.comic.PageKey
import dev.veneranative.data.comic.SourceLoadException
import dev.veneranative.source.api.SearchRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

internal const val AGGREGATE_PREVIEW_SIZE = 8
private const val AGGREGATE_CONCURRENCY = 3

/**
 * One preview page per source, published row by row in [rows].
 *
 * The aggregate search and the source switcher are the same operation seen from two screens, so the
 * fan-out, the concurrency limit and the per-row error isolation live here once: a slow or broken
 * source never hides the ones that already answered, and no screen can drift from the other.
 */
internal fun searchEverySource(
    scope: CoroutineScope,
    catalog: ComicCatalog,
    sources: List<InstalledSource>,
    keyword: String,
    rows: MutableStateFlow<List<AggregateSearchResult>>,
): Job {
    rows.value = sources.map { AggregateSearchResult(it.sourceId, it.name) }
    return scope.launch {
        val semaphore = Semaphore(AGGREGATE_CONCURRENCY)
        coroutineScope {
            sources.map { source ->
                async {
                    semaphore.withPermit {
                        val row = runCatching {
                            val pagingSource = catalog.search(
                                SearchRequest(sourceId = source.sourceId, keyword = keyword, filters = FilterSelection.Empty),
                            )
                            val load = pagingSource.load(
                                PagingSource.LoadParams.Refresh(PageKey.Start, AGGREGATE_PREVIEW_SIZE, false),
                            )
                            when (load) {
                                is PagingSource.LoadResult.Page ->
                                    AggregateSearchResult(source.sourceId, source.name, load.data, false, false)

                                is PagingSource.LoadResult.Error -> throw load.throwable
                                is PagingSource.LoadResult.Invalid ->
                                    AggregateSearchResult(source.sourceId, source.name, emptyList(), false, false)
                            }
                        }.getOrElse { failure ->
                            if (failure is CancellationException) throw failure
                            val detail = ((failure as? SourceLoadException)?.error?.message ?: failure.message)
                                ?.filterNot(Char::isISOControl)
                                ?.take(180)
                            AggregateSearchResult(source.sourceId, source.name, emptyList(), false, true, detail)
                        }
                        rows.value = rows.value.map { if (it.sourceId == source.sourceId) row else it }
                    }
                }
            }.awaitAll()
        }
    }
}
