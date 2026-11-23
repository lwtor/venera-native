package dev.veneranative.data.comic

import dev.veneranative.source.api.CategoryRequest
import dev.veneranative.core.model.TagNavigation
import androidx.paging.PagingSource
import dev.veneranative.core.model.ChapterKey
import dev.veneranative.core.model.Comic
import dev.veneranative.core.model.withListMetadata
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
import dev.veneranative.data.source.SourceCatalogEntry
import dev.veneranative.source.api.SearchRequest
import dev.veneranative.source.api.SourceCore
import dev.veneranative.source.api.SourceOutcome
import dev.veneranative.source.api.SourceScriptRuntime
import dev.veneranative.source.api.SourcePackage
import dev.veneranative.source.api.SourceInstallResult
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.CancellationException
import java.util.UUID

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
    private val probeRuntime: SourceScriptRuntime? = null,
    private val detailCacheTtlMillis: Long = DEFAULT_DETAIL_CACHE_TTL_MILLIS,
    private val capabilityCacheTtlMillis: Long = DEFAULT_CAPABILITY_CACHE_TTL_MILLIS,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val onDiscoveryTiming: (sourceCount: Int, installMillis: Long, capabilityMillis: Long) -> Unit = { _, _, _ -> },
) : ComicCatalog {
    override fun sourceChanges(): kotlinx.coroutines.flow.Flow<Long> = sources.revision

    private fun cacheVersion(version: String?): String = "${sources.revision.value}:$version"

    override suspend fun tagNavigation(comicKey: ComicKey, namespace: String, tag: String) = core.tagNavigation(comicKey, namespace, tag)

    override suspend fun relatedWorksFilters(sourceId: SourceId, target: TagNavigation) = core.relatedWorksFilters(sourceId, target)

    override fun category(request: CategoryRequest): PagingSource<PageKey, Comic> = CategoryPagingSource(core, request, ::prepareResultTags)

    override suspend fun searchCatalog(query: String, entries: List<SourceCatalogEntry>): List<CatalogComicMatch> {
        val keyword = query.trim()
        val runtime = probeRuntime ?: return emptyList()
        if (keyword.isBlank() || entries.isEmpty() || !runtime.isSupported()) return emptyList()
        val semaphore = Semaphore(3)
        return coroutineScope {
            entries.map { entry -> async {
                semaphore.withPermit { probeCatalogEntry(entry, keyword, runtime) }
            } }.awaitAll().filterNotNull()
        }
    }

    private suspend fun probeCatalogEntry(
        entry: SourceCatalogEntry,
        keyword: String,
        runtime: SourceScriptRuntime,
    ): CatalogComicMatch? = try {
        withTimeoutOrNull(CATALOG_PROBE_TIMEOUT_MILLIS) {
            val sourcePackage = sources.packageForProbe(entry.scriptUrl) ?: return@withTimeoutOrNull null
            val probeId = SourceId("catalog-probe-${UUID.randomUUID()}")
            try {
                if (runtime.install(sourcePackage.copy(sourceId = probeId)) !is SourceInstallResult.Installed) {
                    return@withTimeoutOrNull null
                }
                when (val result = core.search(SearchRequest(sourceId = probeId, keyword = keyword))) {
                    is SourceOutcome.Failure -> null
                    is SourceOutcome.Success -> {
                        val candidate = result.value.items.firstOrNull { comic ->
                            comic.title.contains(keyword, ignoreCase = true) || keyword.contains(comic.title, ignoreCase = true)
                        } ?: return@withTimeoutOrNull null
                        val probeComicKey = candidate.key.copy(sourceId = probeId)
                        when (val detail = core.detail(probeComicKey)) {
                            is SourceOutcome.Failure -> null
                            is SourceOutcome.Success -> detail.value.takeIf { it.chapters.isNotEmpty() }?.let {
                                CatalogComicMatch(entry, candidate.copy(key = candidate.key.copy(sourceId = sourcePackage.sourceId)))
                            }
                        }
                    }
                }
            } finally {
                withContext(NonCancellable) {
                    try {
                        runtime.unload(probeId)
                    } finally {
                        sources.clearProbeState(probeId)
                    }
                }
            }
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }

    private data class DetailEntry(
        val mutex: Mutex = Mutex(),
        var sourceVersion: String? = null,
        var expiresAtMillis: Long = 0,
        var value: ComicDetail? = null,
    )

    private val details = ConcurrentHashMap<ComicKey, DetailEntry>()

    private data class TagsEntry(
        val mutex: Mutex = Mutex(),
        var sourceVersion: String? = null,
        var expiresAtMillis: Long = 0,
        var value: List<String>? = null,
        var info: Comic? = null,
    )
    private val tags = ConcurrentHashMap<ComicKey, TagsEntry>()

    override suspend fun comicListInfo(comic: Comic): SourceOutcome<Comic> {
        val version = sources.installed().firstOrNull { it.sourceId == comic.key.sourceId }?.version.let(::cacheVersion)
        val entry = tags.getOrPut(comic.key) { TagsEntry() }
        return entry.mutex.withLock {
            entry.info?.takeIf { entry.sourceVersion == version && nowMillis() < entry.expiresAtMillis }
                ?.let { return@withLock SourceOutcome.Success(comic.withListMetadata(it)) }
            core.comicListInfo(comic).also { result ->
                if (result is SourceOutcome.Success) {
                    entry.info = result.value
                    entry.value = result.value.tags
                    entry.sourceVersion = version
                    entry.expiresAtMillis = nowMillis() + detailCacheTtlMillis
                }
            }
        }
    }

    override suspend fun comicTags(comicKey: ComicKey): SourceOutcome<List<String>> {
        val version = sources.installed().firstOrNull { it.sourceId == comicKey.sourceId }?.version.let(::cacheVersion)
        val entry = tags.getOrPut(comicKey) { TagsEntry() }
        return entry.mutex.withLock {
            entry.value?.takeIf { entry.sourceVersion == version && nowMillis() < entry.expiresAtMillis }
                ?.let { return@withLock SourceOutcome.Success(it) }
            core.comicTags(comicKey).also { result ->
                if (result is SourceOutcome.Success) {
                    entry.sourceVersion = version
                    entry.expiresAtMillis = nowMillis() + detailCacheTtlMillis
                    entry.value = result.value
                    entry.info = null
                }
            }
        }
    }

    private data class CapabilityEntry(
        val sourceVersion: String,
        val expiresAtMillis: Long,
        val value: SourceOutcome<SourceCapabilities>,
    )
    private val capabilities = ConcurrentHashMap<SourceId, CapabilityEntry>()
    private val capabilityLocks = ConcurrentHashMap<SourceId, Mutex>()

    override suspend fun searchableSources(): List<InstalledSource> = usable(SourceCapability.SEARCH)

    override suspend fun explorableSources(): List<InstalledSource> = usable(SourceCapability.EXPLORE)

    override suspend fun capabilities(sourceId: SourceId): SourceOutcome<SourceCapabilities> {
        val sourceVersion = runCatching { sources.installed() }.getOrDefault(emptyList())
            .firstOrNull { it.sourceId == sourceId }?.version
            ?: return core.capabilities(sourceId)
        return cachedCapabilities(sourceId, cacheVersion(sourceVersion))
    }

    override suspend fun detail(comicKey: ComicKey): SourceOutcome<ComicDetail> {
        val installed = sources.installed()
        val sourceVersion = installed.firstOrNull { it.sourceId == comicKey.sourceId }?.version.let(::cacheVersion)
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
        SearchPagingSource(core, request, ::prepareResultTags)

    /** Merge completed metadata only; optional network reads never delay page publishing. */
    private suspend fun prepareResultTags(items: List<Comic>): List<Comic> {
        if (items.firstOrNull()?.key?.sourceId?.value != "copy_manga") return items
        val sourceId = items.first().key.sourceId
        val version = try {
            sources.installed().firstOrNull { it.sourceId == sourceId }?.version.let(::cacheVersion)
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return items
        }
        return items.map { comic ->
            if (comic.tags.any(String::isNotBlank)) return@map comic
            val entry = tags[comic.key] ?: return@map comic
            if (!entry.mutex.tryLock()) return@map comic
            try {
                entry.value?.takeIf { entry.sourceVersion == version && nowMillis() < entry.expiresAtMillis }
                    ?.let { entry.info?.let(comic::withListMetadata) ?: comic.copy(tags = it) } ?: comic
            } finally {
                entry.mutex.unlock()
            }
        }
    }

    private suspend fun usable(capability: SourceCapability): List<InstalledSource> {
        val installedStartedAt = System.nanoTime()
        val installed = runCatching { sources.installed() }.getOrDefault(emptyList())
        val installMillis = (System.nanoTime() - installedStartedAt) / 1_000_000L
        // Source runtimes are isolated by source id, so probes can run concurrently across sources;
        // awaitAll preserves installed order for the picker while avoiding one slow source holding
        // every other source behind its probe.
        val capabilitiesStartedAt = System.nanoTime()
        val usable = coroutineScope {
            installed.map { source ->
                async {
                    source.takeIf {
                        it.enabled && cachedCapabilities(it.sourceId, cacheVersion(it.version))
                            .let { outcome -> outcome is SourceOutcome.Success && outcome.value.supports(capability) }
                    }
                }
            }.awaitAll().filterNotNull()
        }
        onDiscoveryTiming(
            installed.size,
            installMillis,
            (System.nanoTime() - capabilitiesStartedAt) / 1_000_000L,
        )
        return usable
    }

    private suspend fun cachedCapabilities(
        sourceId: SourceId,
        sourceVersion: String,
    ): SourceOutcome<SourceCapabilities> {
        val cached = capabilities[sourceId]
        if (cached != null && cached.sourceVersion == sourceVersion && nowMillis() < cached.expiresAtMillis) {
            return cached.value
        }
        return capabilityLocks.getOrPut(sourceId) { Mutex() }.withLock {
            val current = capabilities[sourceId]
            if (current != null && current.sourceVersion == sourceVersion && nowMillis() < current.expiresAtMillis) {
                return@withLock current.value
            }
            core.capabilities(sourceId).also { outcome ->
                // A failed probe is transient and must be retried next time; only stable successful
                // declarations are cached. Source version changes invalidate the entry immediately.
                if (outcome is SourceOutcome.Success) {
                    capabilities[sourceId] = CapabilityEntry(
                        sourceVersion = sourceVersion,
                        expiresAtMillis = nowMillis() + capabilityCacheTtlMillis,
                        value = outcome,
                    )
                }
            }
        }
    }

    private companion object {
        const val DEFAULT_DETAIL_CACHE_TTL_MILLIS = 5 * 60 * 1000L
        const val DEFAULT_CAPABILITY_CACHE_TTL_MILLIS = 30 * 60 * 1000L
        const val CATALOG_PROBE_TIMEOUT_MILLIS = 15_000L
    }
}
