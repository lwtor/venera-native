package dev.veneranative.feature.search

import dev.veneranative.core.model.Comic
import dev.veneranative.core.model.ComicKey
import dev.veneranative.data.comic.ComicCatalog
import dev.veneranative.source.api.SourceOutcome
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** One visible-row loader shared by ordinary search and related works. */
internal class ResultInfoLoader(private val catalog: ComicCatalog, private val scope: CoroutineScope) {
    private val _info = MutableStateFlow<Map<ComicKey, Comic>>(emptyMap())
    val info = _info.asStateFlow()
    private val _tags = MutableStateFlow<Map<ComicKey, List<String>>>(emptyMap())
    val tags = _tags.asStateFlow()
    private val jobs = mutableMapOf<ComicKey, Job>()
    private val permits = Semaphore(2)

    fun visible(comic: Comic) {
        if ((comic.tags.any(String::isNotBlank) && (comic.key.sourceId.value != "copy_manga" || comic.publicationStatus != null)) ||
            _info.value.containsKey(comic.key) || jobs[comic.key]?.isActive == true) return
        jobs[comic.key] = scope.launch {
            try {
                permits.withPermit {
                    val result = catalog.comicListInfo(comic)
                    coroutineContext.ensureActive()
                    if (result is SourceOutcome.Success) {
                        _tags.update { it + (comic.key to result.value.tags) }
                        _info.update { it + (comic.key to result.value) }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Optional metadata never blocks results; a newly visible failed row can retry.
            } finally {
                if (jobs[comic.key] === coroutineContext[Job]) jobs.remove(comic.key)
            }
        }
    }

    fun hidden(key: ComicKey) { jobs.remove(key)?.cancel() }
}
