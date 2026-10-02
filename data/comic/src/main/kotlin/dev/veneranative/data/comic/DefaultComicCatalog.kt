package dev.veneranative.data.comic

import androidx.paging.PagingSource
import dev.veneranative.core.model.ChapterKey
import dev.veneranative.core.model.Comic
import dev.veneranative.core.model.ComicDetail
import dev.veneranative.core.model.ComicKey
import dev.veneranative.core.model.ExploreItem
import dev.veneranative.core.model.InstalledSource
import dev.veneranative.core.model.SourceCapabilities
import dev.veneranative.core.model.SourceCapability
import dev.veneranative.core.model.SourceId
import dev.veneranative.core.model.SourcePage
import dev.veneranative.source.api.ExploreRequest
import dev.veneranative.data.source.SourceRepository
import dev.veneranative.source.api.SearchRequest
import dev.veneranative.source.api.SourceCore
import dev.veneranative.source.api.SourceOutcome
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * [ComicCatalog] over the installed sources and one [SourceCore].
 *
 * Source ids are declared by the scripts themselves (a script's `key`), so a capability check is a
 * lookup rather than a hardcoded list: the UI offers exactly the sources that can do the job. A
 * source whose capabilities cannot be read is left out of that list instead of failing it — one
 * broken source must not hide the others.
 */
class DefaultComicCatalog(
    private val sources: SourceRepository,
    private val core: SourceCore,
    private val detailCacheTtlMillis: Long = DEFAULT_DETAIL_CACHE_TTL_MILLIS,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) : ComicCatalog {

    private data class DetailEntry(
        val mutex: Mutex = Mutex(),
        var sourceVersion: String? = null,
        var expiresAtMillis: Long = 0,
        var value: ComicDetail? = null,
    )

    private val details = ConcurrentHashMap<ComicKey, DetailEntry>()

    override suspend fun searchableSources(): List<InstalledSource> = usable(SourceCapability.SEARCH)

    override suspend fun explorableSources(): List<InstalledSource> = usable(SourceCapability.EXPLORE)

    override suspend fun capabilities(sourceId: SourceId): SourceOutcome<SourceCapabilities> =
        core.capabilities(sourceId)

    override suspend fun detail(comicKey: ComicKey): SourceOutcome<ComicDetail> {
        val installed = sources.installed()
        val sourceVersion = installed.firstOrNull { it.sourceId == comicKey.sourceId }?.version
        val entry = details.getOrPut(comicKey) { DetailEntry() }
        return entry.mutex.withLock {
            entry.value?.takeIf {
                entry.sourceVersion == sourceVersion && nowMillis() < entry.expiresAtMillis
            }?.let { return@withLock SourceOutcome.Success(it) }

            when (val result = core.detail(comicKey)) {
                is SourceOutcome.Success -> {
                    entry.sourceVersion = sourceVersion
                    entry.expiresAtMillis = nowMillis() + detailCacheTtlMillis
                    entry.value = result.value
                    result
                }
                is SourceOutcome.Failure -> {
                    entry.value = null
                    result
                }
            }
        }
    }

    override suspend fun refreshDetail(comicKey: ComicKey): SourceOutcome<ComicDetail> {
        details.remove(comicKey)
        return detail(comicKey)
    }

    override suspend fun pages(chapterKey: ChapterKey): SourceOutcome<List<SourcePage>> {
        sources.installed()
        return core.pages(chapterKey)
    }

    /**
     * Resolved from the installed list rather than from the runtime: a source that was uninstalled or
     * switched off is not in the list, and asking the runtime would report it as "not loaded" — which
     * is an engine detail, not the reason the user sees.
     */
    override suspend fun enabledSource(sourceId: SourceId): InstalledSource? =
        runCatching { sources.installed() }.getOrDefault(emptyList())
            .firstOrNull { it.sourceId == sourceId && it.enabled }

    override fun explore(request: ExploreRequest): PagingSource<PageKey, ExploreItem> =
        ExplorePagingSource(core, request)

    override fun search(request: SearchRequest): PagingSource<PageKey, Comic> =
        SearchPagingSource(core, request)

    private suspend fun usable(capability: SourceCapability): List<InstalledSource> {
        val installed = runCatching { sources.installed() }.getOrDefault(emptyList())
        // A plain loop: reading capabilities suspends, and the order of the installed list is the
        // order the user sees.
        val usable = mutableListOf<InstalledSource>()
        for (source in installed) {
            if (source.enabled && source.sourceId.supports(capability)) {
                usable += source
            }
        }
        return usable
    }

    private suspend fun SourceId.supports(capability: SourceCapability): Boolean =
        when (val outcome = core.capabilities(this)) {
            is SourceOutcome.Success -> outcome.value.supports(capability)
            is SourceOutcome.Failure -> false
        }

    private companion object {
        const val DEFAULT_DETAIL_CACHE_TTL_MILLIS = 5 * 60 * 1000L
    }
}
