package dev.veneranative.feature.details

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.veneranative.core.model.ComicDetail
import dev.veneranative.core.model.ComicKey
import dev.veneranative.core.model.ComicRef
import dev.veneranative.core.model.ChapterKey
import dev.veneranative.core.model.ChapterRef
import dev.veneranative.data.collection.CollectionRepository
import dev.veneranative.data.collection.ComicSnapshot
import dev.veneranative.data.collection.DEFAULT_SHELF_FOLDER_ID
import dev.veneranative.data.comic.ComicCatalog
import dev.veneranative.data.download.DownloadRepository
import dev.veneranative.data.history.HistoryRepository
import dev.veneranative.data.settings.ScreenPreferenceRepository
import dev.veneranative.source.api.SourceOutcome
import dev.veneranative.source.api.SourceRuntimeError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Loads one comic's details, owns the chapter list's presentation and keeps the comic on the shelf.
 *
 * Which is checked first matters: a comic whose source was uninstalled or switched off must be told
 * apart from a source that failed to answer, because only the second one is worth retrying.
 *
 * The shelf is nullable on purpose. The assembly layer builds it from the database, which is not
 * there for the first moments of the process, and a comic should still be readable while that
 * happens — so until it arrives the screen simply offers no way to keep the comic.
 */
class DetailsViewModel(
    private val catalog: ComicCatalog,
    private val comicKey: ComicKey,
    private val collection: CollectionRepository?,
    private val downloads: DownloadRepository? = null,
    private val history: HistoryRepository? = null,
    screenPreferences: ScreenPreferenceRepository? = null,
) : ViewModel() {

    private var screenPreferences: ScreenPreferenceRepository? = screenPreferences
    private var presentationSelectionChanged = false

    private val comicRef = ComicRef.Remote(comicKey)

    private val _state = MutableStateFlow(DetailsUiState(hasShelf = collection != null))
    val state: StateFlow<DetailsUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val order = runCatching { screenPreferences?.get(orderPreferenceKey()) }.getOrNull()
                ?.let { value -> ChapterOrder.entries.firstOrNull { it.name == value } }
            val group = runCatching { screenPreferences?.get(groupPreferenceKey()) }.getOrNull()
                ?.takeUnless { it == ALL_VERSIONS }
            _state.update { it.copy(order = order ?: it.order, selectedGroup = group) }
            load()
        }
        observeShelf()
        observeReadChapters()
    }

    fun onAction(action: DetailsAction) {
        when (action) {
            DetailsAction.Retry, DetailsAction.Refresh -> load(forceRefresh = true)

            is DetailsAction.GroupSelected -> {
                presentationSelectionChanged = true
                _state.update { it.copy(selectedGroup = action.group) }
                persist(groupPreferenceKey(), action.group ?: ALL_VERSIONS)
            }

            is DetailsAction.OrderSelected -> {
                presentationSelectionChanged = true
                _state.update { it.copy(order = action.order) }
                persist(orderPreferenceKey(), action.order.name)
            }

            is DetailsAction.ChapterQueryChanged -> _state.update { it.copy(chapterQuery = action.query) }

            is DetailsAction.DescriptionExpanded -> _state.update { it.copy(descriptionExpanded = action.expanded) }

            is DetailsAction.ChapterSelectionModeChanged -> _state.update {
                it.copy(isChapterSelectionMode = action.enabled, selectedChapters = if (action.enabled) it.selectedChapters else emptySet())
            }

            is DetailsAction.ChapterSelectionToggled -> _state.update { current ->
                val selected = current.selectedChapters
                current.copy(selectedChapters = if (action.chapter in selected) selected - action.chapter else selected + action.chapter)
            }

            is DetailsAction.VisibleChaptersSelected -> _state.update { current ->
                val visibleKeys = current.filteredChapters.map { it.key }.toSet()
                current.copy(
                    selectedChapters = if (action.selected) current.selectedChapters + visibleKeys
                    else current.selectedChapters - visibleKeys,
                )
            }

            DetailsAction.DownloadSelectedChapters -> downloadChapters(_state.value.selectedChapters)

            DetailsAction.ToggleFavorite -> toggleFavorite()
        }
    }

    fun attachScreenPreferences(repository: ScreenPreferenceRepository) {
        if (screenPreferences === repository) return
        screenPreferences = repository
        viewModelScope.launch {
            val order = runCatching { repository.get(orderPreferenceKey()) }.getOrNull()
                ?.let { value -> ChapterOrder.entries.firstOrNull { it.name == value } }
            val group = runCatching { repository.get(groupPreferenceKey()) }.getOrNull()
                ?.takeUnless { it == ALL_VERSIONS }
            if (!presentationSelectionChanged) {
                _state.update { it.copy(order = order ?: it.order, selectedGroup = group) }
            }
        }
    }

    /** Refreshes the resume target when the details route becomes visible again after reading. */
    fun refreshReadingProgress(repository: HistoryRepository? = history) {
        repository ?: return
        viewModelScope.launch {
            try {
                val progress = repository.progress(comicKey)
                _state.update { it.copy(lastReadChapterId = progress?.chapterId) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Reading history is optional; its failure must not block comic details.
            }
        }
    }

    /**
     * Watches whether this comic is on the shelf.
     *
     * The answer comes from the shelf, not from what this screen last wrote, so removing the comic
     * from the shelf itself is visible here without the two having to know about each other.
     */
    private fun observeShelf() {
        val repository = collection ?: return
        viewModelScope.launch {
            runCatching {
                repository.observeItem(comicRef).collect { item ->
                    _state.update { it.copy(isFavorite = item != null) }
                }
            }.onFailure { failure -> failUnlessCancelled(failure) }
        }
    }

    private fun observeReadChapters() {
        val repository = history ?: return
        viewModelScope.launch {
            runCatching {
                repository.observeComicHistory(comicKey).collect { entries ->
                    _state.update { current ->
                        current.copy(
                            readChapterIds = entries.asSequence()
                                .filter { it.pageCount > 0 && it.pageIndex >= it.pageCount - 1 }
                                .map { it.chapterId }
                                .toSet(),
                        )
                    }
                }
            }.onFailure { failure -> failUnlessCancelled(failure) }
        }
    }

    private fun toggleFavorite() {
        val repository = collection ?: return
        // Nothing to keep before the source answered: a title is the least a shelf row must have.
        val detail = _state.value.detail ?: return
        viewModelScope.launch {
            _state.update { it.copy(shelfMessage = null) }
            runCatching {
                if (_state.value.isFavorite) {
                    repository.remove(comicRef)
                } else {
                    repository.add(comicRef, DEFAULT_SHELF_FOLDER_ID, detail.toSnapshot())
                }
            }.onFailure { failure ->
                failUnlessCancelled(failure)
                _state.update { it.copy(shelfMessage = "无法保存此更改。") }
            }
        }
    }

    private fun downloadChapters(chapters: Set<ChapterKey>) {
        if (chapters.isEmpty()) return
        val repository = downloads ?: run {
            _state.update { it.copy(downloadMessage = "下载服务尚未就绪。") }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(downloadMessage = null, isBatchDownloading = true) }
            var queued = 0
            val failed = linkedSetOf<ChapterKey>()
            val detail = _state.value.detail ?: run {
                _state.update { it.copy(isBatchDownloading = false) }
                return@launch
            }
            val ordered = detail.chapters.filter { it.key in chapters }
            ordered.forEach { item ->
                try {
                    when (val outcome = catalog.pages(item.key)) {
                        is SourceOutcome.Success -> {
                            repository.enqueue(
                                ChapterRef.Remote(item.key),
                                item.title,
                                outcome.value,
                                detail.comic.title,
                            )
                            queued++
                        }
                        is SourceOutcome.Failure -> failed += item.key
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    failed += item.key
                }
            }
            val message = when {
                queued == 0 && failed.isEmpty() -> "没有可加入下载的章节。"
                failed.isEmpty() && queued == 1 -> "章节已加入下载队列。"
                failed.isEmpty() -> "已将 $queued 个章节加入下载队列。"
                queued == 0 -> "所选章节暂时无法下载，请稍后重试。"
                else -> "已加入 $queued 个章节，${failed.size} 个章节加载失败。"
            }
            _state.update {
                it.copy(
                    downloadMessage = message,
                    downloadQueueVersion = it.downloadQueueVersion + queued,
                    isBatchDownloading = false,
                    selectedChapters = failed,
                    isChapterSelectionMode = failed.isNotEmpty(),
                )
            }
        }
    }

    private fun load(forceRefresh: Boolean = false) {
        _state.update { it.copy(status = DetailsStatus.Loading, message = null) }
        viewModelScope.launch {
            val source = catalog.enabledSource(comicKey.sourceId)
            if (source == null) {
                _state.update {
                    it.copy(
                        status = DetailsStatus.SourceUnavailable,
                        detail = null,
                        sourceName = null,
                        message = null,
                    )
                }
                return@launch
            }

            val outcome = if (forceRefresh) catalog.refreshDetail(comicKey) else catalog.detail(comicKey)
            when (outcome) {
                is SourceOutcome.Success -> _state.update { current ->
                    current.copy(
                        status = DetailsStatus.Ready,
                        detail = outcome.value,
                        sourceName = source.name,
                        // A refresh can drop the group the user had selected.
                        selectedGroup = current.selectedGroup?.takeIf { group ->
                            outcome.value.chapters.any { it.group == group }
                        },
                        message = null,
                    )
                }

                is SourceOutcome.Failure -> _state.update {
                    it.copy(
                        status = DetailsStatus.Failed,
                        sourceName = source.name,
                        message = outcome.error.toDetailsMessage(),
                    )
                }
            }
        }
    }

    /**
     * Leaving the screen is not a failure the user should be told about, so cancellation travels on
     * instead of being reported as copy.
     */
    private fun failUnlessCancelled(failure: Throwable) {
        if (failure is CancellationException) throw failure
    }

    private fun orderPreferenceKey(): String = "details.${comicPreferenceId()}.chapter-order"

    private fun groupPreferenceKey(): String = "details.${comicPreferenceId()}.chapter-version"

    private fun comicPreferenceId(): String = "${comicKey.sourceId.value.length}:${comicKey.sourceId.value}:${comicKey.remoteId.value}"

    private fun persist(key: String, value: String) {
        val preferences = screenPreferences ?: return
        viewModelScope.launch { runCatching { preferences.put(key, value) } }
    }

    private companion object {
        const val ALL_VERSIONS = "@all"
    }
}

/**
 * What the shelf is told about a comic when it is kept.
 *
 * Chapter facts are recorded only when the source actually listed chapters: a comic with none is
 * "unknown" rather than "empty", and reporting zero would make the next real answer look like an
 * update. "Last" is whatever the source ended with, exactly what an update check compares against.
 */
internal fun ComicDetail.toSnapshot(): ComicSnapshot = ComicSnapshot(
    title = comic.title,
    subtitle = comic.subtitle,
    coverRef = comic.coverUrl,
    chapterCount = chapters.size.takeIf { it > 0 },
    latestChapterId = chapters.lastOrNull()?.key?.remoteId?.value,
)

/**
 * Product copy for a failed load.
 *
 * Exhaustive on purpose, with generic wording as the fallback: a lower layer's diagnostic text is
 * written for whoever debugs the source, not for the person reading the comic.
 */
internal fun SourceRuntimeError.toDetailsMessage(): String = when (this) {
    is SourceRuntimeError.UnsupportedCapability -> "此漫画源不支持详情页面。"
    is SourceRuntimeError.SourceNotLoaded -> "此漫画源已无法使用。"
    is SourceRuntimeError.Timeout -> "漫画源响应超时。"
    is SourceRuntimeError.Cancelled -> "加载已取消。"
    is SourceRuntimeError.Busy -> "漫画源正在处理其他请求，请稍后重试。"
    is SourceRuntimeError.EngineUnavailable -> "此设备暂时无法运行漫画源。"
    is SourceRuntimeError.EngineTerminated ->
        "漫画源运行环境已停止，请重新打开漫画。"

    is SourceRuntimeError.RuntimeClosed -> "此设备暂时无法运行漫画源。"

    is SourceRuntimeError.InvalidPackage,
    is SourceRuntimeError.InvalidCall,
    is SourceRuntimeError.ScriptSyntax,
    is SourceRuntimeError.ScriptExecution,
    is SourceRuntimeError.Internal,
    -> "漫画源暂时无法响应。"
}
