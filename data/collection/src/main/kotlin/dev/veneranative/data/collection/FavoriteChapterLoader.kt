package dev.veneranative.data.collection

import dev.veneranative.core.model.ComicRef
import dev.veneranative.core.model.comicChapterProgressLabel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** Serialized by its owner's scope (ViewModel main dispatcher). Only visible, missing rows load. */
class FavoriteChapterLoader(private val repository: CollectionRepository, private val scope: CoroutineScope) {
    private val permits = Semaphore(2)
    private val jobs = mutableMapOf<ComicRef, Job>()
    private val attempted = mutableSetOf<ComicRef>()
    private val mutableStates = MutableStateFlow<Map<ComicRef, FavoriteCatalogState>>(emptyMap())
    val states: StateFlow<Map<ComicRef, FavoriteCatalogState>> = mutableStates.asStateFlow()

    fun visible(item: FavoriteItem) {
        val ref = item.ref
        if ((item.chapters.isNotEmpty() && item.hasStoredCover) || ref !is ComicRef.Remote || ref in jobs || ref in attempted) return
        val job = scope.launch(start = CoroutineStart.LAZY) {
            mutableStates.update { it + (ref to FavoriteCatalogState.Loading) }
            try {
                val loaded = permits.withPermit { repository.ensureChapters(ref) }
                currentCoroutineContext().ensureActive()
                attempted += ref
                mutableStates.update { if (loaded) it - ref else it + (ref to FavoriteCatalogState.Failed) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                attempted += ref
                mutableStates.update { it + (ref to FavoriteCatalogState.Failed) }
            } finally {
                if (jobs[ref] === currentCoroutineContext()[Job]) {
                    jobs.remove(ref)
                    mutableStates.update { if (it[ref] == FavoriteCatalogState.Loading) it - ref else it }
                }
            }
        }
        jobs[ref] = job
        job.start()
    }

    fun hidden(ref: ComicRef) {
        attempted.remove(ref)
        jobs.remove(ref)?.cancel()
        mutableStates.update { if (it[ref] == FavoriteCatalogState.Loading) it - ref else it }
    }
}

enum class FavoriteCatalogState { Loading, Failed }

fun favoriteChapterLabel(item: FavoriteItem, state: FavoriteCatalogState?): String {
    val progress = comicChapterProgressLabel(item.chapters, item.readingChapter)
    if (item.chapters.isNotEmpty()) return progress
    if (item.readingChapter != null) return when (state) {
        FavoriteCatalogState.Loading -> progress.removeSuffix("/未知") + "/加载中"
        FavoriteCatalogState.Failed -> progress.removeSuffix("/未知") + "/未获取"
        null -> progress
    }
    return when (state) {
        FavoriteCatalogState.Loading -> "加载章节…"
        FavoriteCatalogState.Failed -> "章节未获取"
        null -> progress
    }
}
