package dev.veneranative.feature.details

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingSource
import dev.veneranative.core.model.Comic
import dev.veneranative.core.model.ComicDetail
import dev.veneranative.core.model.ComicKey
import dev.veneranative.core.model.ComicRef
import dev.veneranative.core.model.FilterSelection
import dev.veneranative.core.model.ChapterKey
import dev.veneranative.core.model.ChapterRef
import dev.veneranative.data.collection.CollectionRepository
import dev.veneranative.data.collection.CollectionNameConflictException
import dev.veneranative.data.collection.ComicSnapshot
import dev.veneranative.data.comic.ComicCatalog
import kotlinx.coroutines.Job
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException
import dev.veneranative.data.download.DownloadRepository
import dev.veneranative.data.download.DownloadChapterState
import dev.veneranative.data.history.HistoryRepository
import dev.veneranative.data.settings.ScreenPreferenceRepository
import dev.veneranative.data.comic.PageKey
import dev.veneranative.source.api.SearchRequest
import dev.veneranative.source.api.SourceOutcome
import dev.veneranative.source.api.SourceRuntimeError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.drop
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
    private val titleHint: String? = null,
    private val loadTimeoutMillis: Long = 120_000L,
) : ViewModel() {

    private var screenPreferences: ScreenPreferenceRepository? = screenPreferences
    private var presentationSelectionChanged = false
    private var nextChapterLocationRequest = 0L
    private var loadJob: Job? = null
    private var sourceSwitchJob: Job? = null

    private val comicRef = ComicRef.Remote(comicKey)

    private val _state = MutableStateFlow(
        DetailsUiState(hasShelf = collection != null, titleHint = titleHint)
    )
    val state: StateFlow<DetailsUiState> = _state.asStateFlow()

    /** Clears route-local editing and result UI when this details screen is opened again. */
    fun onScreenEntered() {
        _state.update {
            it.copy(
                isChapterSelectionMode = false,
                selectedChapters = emptySet(),
                downloadMessage = null,
                chapterLocationRequest = null,
                tagNavigation = null,
                tagMessage = null,
            )
        }
    }

    init {
        viewModelScope.launch {
            val order = runCatching { screenPreferences?.get(orderPreferenceKey()) }.getOrNull()
                ?.let { value -> ChapterOrder.entries.firstOrNull { it.name == value } }
            val group = runCatching { screenPreferences?.get(groupPreferenceKey()) }.getOrNull()
                ?.takeUnless { it == ALL_VERSIONS }
            _state.update { it.copy(order = order ?: it.order, selectedGroup = group) }
            load()
        }
        viewModelScope.launch { catalog.sourceChanges().drop(1).collect { load(forceRefresh = true) } }
        observeShelf()
        observeReadChapters()
        observeDownloadedChapters()
    }

    private fun observeDownloadedChapters() {
        val repository = downloads ?: return
        viewModelScope.launch {
            repository.observeTasks().collect { tasks ->
                val downloaded = tasks.asSequence()
                    .filter { it.state == DownloadChapterState.Completed }
                    .mapNotNull { it.chapter as? ChapterRef.Remote }
                    .filter { it.key.comicKey == comicKey }
                    .toSet()
                _state.update { current ->
                    val updated = current.copy(downloadedChapters = downloaded)
                    updated.copy(
                        selectedChapters = updated.selectedChapters.filterNotTo(linkedSetOf()) { key ->
                            updated.detail?.chapters.orEmpty().any { it.key == key && updated.isDownloaded(it) }
                        },
                    )
                }
            }
        }
    }

    fun onAction(action: DetailsAction) {
        when (action) {
            DetailsAction.Retry, DetailsAction.Refresh -> load(forceRefresh = true)

            is DetailsAction.TagClicked -> openTag(action)
            DetailsAction.TagNavigationConsumed -> _state.update { it.copy(tagNavigation = null) }
            DetailsAction.TagMessageConsumed -> _state.update { it.copy(tagMessage = null) }

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

            DetailsAction.LocateCurrentChapter -> {
                val current = _state.value
                val chapter = current.currentReadingChapter
                if (chapter != null && !current.isChapterSelectionMode && current.status != DetailsStatus.SourceUnavailable) {
                    val group = if (current.selectedGroup != null && current.selectedGroup != chapter.group) chapter.group else current.selectedGroup
                    presentationSelectionChanged = true
                    val requestId = ++nextChapterLocationRequest
                    _state.update {
                        it.copy(selectedGroup = group, chapterQuery = "", chapterLocationRequest = requestId)
                    }
                    persist(groupPreferenceKey(), group ?: ALL_VERSIONS)
                }
            }

            is DetailsAction.ChapterLocationHandled -> _state.update {
                if (it.chapterLocationRequest == action.requestId) it.copy(chapterLocationRequest = null) else it
            }

            is DetailsAction.DescriptionExpanded -> _state.update { it.copy(descriptionExpanded = action.expanded) }

            is DetailsAction.ChapterSelectionModeChanged -> _state.update {
                it.copy(isChapterSelectionMode = action.enabled, selectedChapters = if (action.enabled) it.selectedChapters else emptySet())
            }

            is DetailsAction.ChapterSelectionToggled -> _state.update { current ->
                if (current.detail?.chapters.orEmpty().any { it.key == action.chapter && current.isDownloaded(it) }) {
                    return@update current
                }
                val selected = current.selectedChapters
                current.copy(selectedChapters = if (action.chapter in selected) selected - action.chapter else selected + action.chapter)
            }

            is DetailsAction.ChapterSelectionRangeChanged -> _state.update { current ->
                val chapters = current.filteredChapters
                val start = chapters.indexOfFirst { it.key == action.start }
                val end = chapters.indexOfFirst { it.key == action.end }
                if (start < 0 || end < 0) current else {
                    val range = chapters.subList(minOf(start, end), maxOf(start, end) + 1).map { it.key }.toSet()
                    current.copy(
                        selectedChapters = if (action.selected) current.selectedChapters + range.filter { key ->
                            chapters.none { it.key == key && current.isDownloaded(it) }
                        }
                        else current.selectedChapters - range,
                    )
                }
            }

            is DetailsAction.VisibleChaptersSelected -> _state.update { current ->
                val visibleKeys = current.filteredChapters.map { it.key }.toSet()
                val selectableKeys = current.selectableChapters.map { it.key }.toSet()
                current.copy(
                    selectedChapters = if (action.selected) current.selectedChapters + selectableKeys
                    else current.selectedChapters - visibleKeys,
                )
            }

            DetailsAction.DownloadSelectedChapters -> downloadChapters(_state.value.selectedChapters)

            DetailsAction.ToggleFavorite -> toggleFavorite()
            DetailsAction.RemoveFavoriteRequested -> _state.update { current ->
                if (current.favoriteDialog == FavoriteDialog.Add && current.isFavorite) {
                    current.copy(favoriteDialog = FavoriteDialog.Remove, newFavoriteFolderDraft = null)
                } else current
            }
            is DetailsAction.FavoriteFolderToggled -> _state.update { current ->
                if (current.favoriteFolders.none { it.id == action.folderId }) current
                else current.copy(favoriteFolderSelection = current.favoriteFolderSelection.let { ids ->
                    if (action.folderId in ids) ids - action.folderId else ids + action.folderId
                })
            }
            DetailsAction.ConfirmFavorite -> confirmFavorite()
            DetailsAction.DismissFavoriteDialog -> _state.update { it.copy(favoriteDialog = null, newFavoriteFolderDraft = null) }
            DetailsAction.NewFavoriteFolderRequested -> _state.update { it.copy(newFavoriteFolderDraft = "") }
            is DetailsAction.NewFavoriteFolderDraftChanged -> _state.update { it.copy(newFavoriteFolderDraft = action.draft) }
            DetailsAction.CreateFavoriteFolder -> createFavoriteFolder()
            DetailsAction.DismissNewFavoriteFolder -> _state.update { it.copy(newFavoriteFolderDraft = null) }

            DetailsAction.OpenSourceSwitch -> openSourceSwitch()
            DetailsAction.CloseSourceSwitch -> closeSourceSwitch()
            is DetailsAction.SourceSwitchQueryChanged -> _state.update { it.copy(sourceSwitchQuery = action.query) }
            DetailsAction.SearchOtherSources -> searchOtherSources()
            is DetailsAction.SourceSwitchResultSelected -> _state.update {
                it.copy(sourceSwitchSelection = action.comic, sourceSwitchMessage = null)
            }
            DetailsAction.ReplaceSource -> replaceSource()
            DetailsAction.SourceSwitchTargetConsumed -> _state.update { it.copy(sourceSwitchTarget = null) }
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
                    _state.update { current ->
                        val folderIds = item?.folderIds.orEmpty()
                        current.copy(
                            isFavorite = item != null,
                            // The shelf remembers the title of a comic whose source is gone; without it
                            // there would be nothing to search the other sources with.
                            titleHint = current.titleHint ?: item?.title?.takeIf { it.isNotBlank() },
                            favoriteFolderIds = folderIds,
                            favoriteFolderSelection = if (current.favoriteDialog == null) {
                                folderIds
                            } else {
                                current.favoriteFolderSelection
                            },
                        )
                    }
                }
            }.onFailure { failure -> failUnlessCancelled(failure) }
        }
        viewModelScope.launch {
            runCatching {
                repository.observeFolders().collect { folders ->
                    _state.update { current -> current.copy(favoriteFolders = folders) }
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
                            lastReadGroup = entries.maxByOrNull { it.updatedAtEpochMillis }?.chapterGroup,
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
        collection ?: return
        // Nothing to keep before the source answered: a title is the least a shelf row must have.
        _state.value.detail ?: return
        if (_state.value.favoriteSaving) return
        _state.update { current -> current.copy(
            favoriteDialog = FavoriteDialog.Add,
            favoriteFolderSelection = current.favoriteFolderIds,
            newFavoriteFolderDraft = null,
            shelfMessage = null,
        ) }
    }

    private fun confirmFavorite() {
        val repository = collection ?: return
        val detail = _state.value.detail ?: return
        val dialog = _state.value.favoriteDialog ?: return
        if (_state.value.favoriteSaving) return
        val selected = _state.value.favoriteFolderSelection
        val wasFavorite = _state.value.isFavorite
        _state.update { it.copy(favoriteSaving = true, shelfMessage = null) }
        viewModelScope.launch {
            runCatching {
                if (dialog == FavoriteDialog.Remove) {
                    repository.remove(comicRef)
                } else if (wasFavorite) {
                    repository.setFolders(comicRef, selected)
                } else {
                    repository.addToFolders(comicRef, selected, detail.toSnapshot())
                }
            }.onSuccess {
                _state.update { it.copy(favoriteDialog = null, favoriteSaving = false) }
            }.onFailure { failure ->
                failUnlessCancelled(failure)
                _state.update { it.copy(favoriteSaving = false, shelfMessage = "无法保存此更改。") }
            }
        }
    }

    private fun createFavoriteFolder() {
        val repository = collection ?: return
        val draft = _state.value.newFavoriteFolderDraft?.trim() ?: return
        if (draft.isEmpty()) {
            _state.update { it.copy(shelfMessage = "请输入收藏夹名称。") }
            return
        }
        viewModelScope.launch {
            runCatching { repository.createFolder(draft) }
                .onSuccess { id -> _state.update {
                    it.copy(newFavoriteFolderDraft = null, favoriteFolderSelection = it.favoriteFolderSelection + id)
                } }
                .onFailure { failure ->
                    failUnlessCancelled(failure)
                    _state.update {
                        it.copy(
                            shelfMessage = if (failure is CollectionNameConflictException) {
                                "已有同名收藏夹，请换个名称。"
                            } else {
                                "无法创建收藏夹。"
                            },
                        )
                    }
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
            val current = _state.value
            val ordered = detail.chapters.filter { it.key in chapters && !current.isDownloaded(it) }
            if (ordered.isEmpty()) {
                _state.update {
                    it.copy(
                        downloadMessage = "所选章节已经下载或没有待下载章节。",
                        isBatchDownloading = false,
                        selectedChapters = emptySet(),
                        isChapterSelectionMode = false,
                    )
                }
                return@launch
            }
            ordered.forEach { item ->
                try {
                    when (val outcome = catalog.pages(item.key)) {
                        is SourceOutcome.Success -> {
                            if (outcome.value.isEmpty()) {
                                failed += item.key
                            } else {
                                repository.enqueue(
                                    ChapterRef.Remote(item.key, item.group),
                                    item.title,
                                    outcome.value,
                                    detail.comic.title,
                                    item.index,
                                )
                                queued++
                            }
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

    private fun openTag(action: DetailsAction.TagClicked) {
        val current = _state.value
        if (!current.canOpenTags || current.openingTag || action.tag.isBlank() ||
            action.tag !in current.detail?.tagGroups?.get(action.namespace).orEmpty()) return
        _state.update { it.copy(openingTag = true, tagMessage = null) }
        viewModelScope.launch {
            try {
                when (val result = catalog.tagNavigation(comicKey, action.namespace, action.tag)) {
                    is SourceOutcome.Success -> _state.update {
                        it.copy(tagNavigation = result.value?.let { target -> TagNavigationRequest(action.tag, target) },
                            tagMessage = if (result.value == null) "此漫画源暂不支持该标签的作品检索。" else null)
                    }
                    is SourceOutcome.Failure -> _state.update { it.copy(tagMessage = "无法打开相关作品，请稍后重试。") }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                _state.update { it.copy(tagMessage = "无法打开相关作品，请稍后重试。") }
            } finally {
                _state.update { it.copy(openingTag = false) }
            }
        }
    }

    /**
     * Opens the source switcher, seeded with the title we already know. On a failed or removed
     * source there is no detail to read a title from, which is why the route carries one.
     */
    private fun openSourceSwitch() {
        val seed = _state.value.detail?.comic?.title ?: _state.value.titleHint.orEmpty()
        _state.update {
            it.copy(
                sourceSwitchOpen = true,
                sourceSwitchQuery = if (it.sourceSwitchQuery.isBlank()) seed else it.sourceSwitchQuery,
                sourceSwitchSelection = null,
                sourceSwitchMessage = null,
            )
        }
        if (seed.isNotBlank()) searchOtherSources()
    }

    private fun closeSourceSwitch() {
        sourceSwitchJob?.cancel()
        sourceSwitchJob = null
        _state.update {
            it.copy(
                sourceSwitchOpen = false,
                sourceSwitchRows = emptyList(),
                sourceSwitchSearching = false,
                sourceSwitchSelection = null,
                sourceSwitchMessage = null,
            )
        }
    }

    /**
     * Asks every other searchable source for the first page of the same title.
     *
     * Each source owns one row that is replaced as soon as it answers, mirroring the aggregate
     * search: a slow or broken source never hides the ones that already replied. Results are
     * fetched on a background job so leaving the panel cancels outstanding network work.
     */
    private fun searchOtherSources() {
        sourceSwitchJob?.cancel()
        val keyword = _state.value.sourceSwitchQuery.trim()
        if (keyword.isBlank()) {
            _state.update {
                it.copy(
                    sourceSwitchRows = emptyList(),
                    sourceSwitchSearching = false,
                    sourceSwitchSelection = null,
                    sourceSwitchMessage = "请输入漫画标题后搜索。",
                )
            }
            return
        }
        sourceSwitchJob = viewModelScope.launch {
            val sources = try {
                catalog.searchableSources().filter { it.sourceId != comicKey.sourceId }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                _state.update { it.copy(sourceSwitchSearching = false, sourceSwitchMessage = "暂时无法获取漫画源列表。") }
                return@launch
            }
            _state.update {
                it.copy(
                    sourceSwitchRows = sources.map { source ->
                        SourceSwitchRow(sourceId = source.sourceId, sourceName = source.name)
                    },
                    sourceSwitchSearching = true,
                    sourceSwitchSelection = null,
                    sourceSwitchMessage = null,
                )
            }
            if (sources.isEmpty()) {
                _state.update { it.copy(sourceSwitchSearching = false, sourceSwitchMessage = "没有其他可用的漫画源。") }
                return@launch
            }
            sources.forEach { source ->
                try {
                    val paging = catalog.search(
                        SearchRequest(sourceId = source.sourceId, keyword = keyword, filters = FilterSelection.Empty)
                    )
                    val loaded = paging.load(
                        PagingSource.LoadParams.Refresh(key = PageKey.Start, loadSize = 8, placeholdersEnabled = false)
                    )
                    val (comics, failed) = when (loaded) {
                        is PagingSource.LoadResult.Page -> loaded.data to false
                        is PagingSource.LoadResult.Error -> emptyList<Comic>() to true
                        else -> emptyList<Comic>() to false
                    }
                    _state.update { current ->
                        current.copy(sourceSwitchRows = current.sourceSwitchRows.map { row ->
                            if (row.sourceId == source.sourceId) {
                                row.copy(comics = comics, isLoading = false, hasError = failed)
                            } else row
                        })
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    _state.update { current ->
                        current.copy(sourceSwitchRows = current.sourceSwitchRows.map { row ->
                            if (row.sourceId == source.sourceId) row.copy(isLoading = false, hasError = true) else row
                        })
                    }
                }
            }
            _state.update { it.copy(sourceSwitchSearching = false) }
        }
    }

    /**
     * Rebinds the shelf entry to the chosen comic, then hands the new key to the assembly layer.
     *
     * Migrating the favourite is best effort: a comic that was never kept has nothing to move, and
     * even a failed rebind must not stop the user from reading the comic on the other source. The
     * message says plainly that progress and downloads stay behind, since chapter ids differ.
     */
    private fun replaceSource() {
        if (_state.value.sourceSwitchTarget != null) return
        val comic = _state.value.sourceSwitchSelection ?: run {
            _state.update { it.copy(sourceSwitchMessage = "请先选择要替换的搜索结果。") }
            return
        }
        val target = comic.key
        if (target == comicKey) {
            _state.update { it.copy(sourceSwitchMessage = "这是当前漫画源。") }
            return
        }
        viewModelScope.launch {
            var moved = false
            var failed = false
            if (collection != null) {
                try {
                    moved = collection.replaceSource(ComicRef.Remote(comicKey), ComicRef.Remote(target))
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    failed = true
                }
            }
            _state.update {
                it.copy(
                    sourceSwitchTarget = target,
                    sourceSwitchMessage = when {
                        failed -> "已打开新来源，但收藏迁移失败，请稍后在书架手动处理。"
                        moved -> "已切换到新来源并保留收藏；阅读进度和已下载章节不会迁移。"
                        else -> "已切换到新来源；阅读进度和已下载章节不会迁移。"
                    },
                )
            }
        }
    }

    private fun load(forceRefresh: Boolean = false) {
        loadJob?.cancel()
        _state.update { it.copy(status = DetailsStatus.Loading, message = null) }
        loadJob = viewModelScope.launch {
            try {
                withTimeout(loadTimeoutMillis) {
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
                        return@withTimeout
                    }

                    val outcome = if (forceRefresh) catalog.refreshDetail(comicKey) else catalog.detail(comicKey)
                    val canOpenTags = if (outcome is SourceOutcome.Success)
                        (catalog.capabilities(comicKey.sourceId) as? SourceOutcome.Success)?.value?.canNavigateTags == true
                        else false
                    if (outcome is SourceOutcome.Success) collection?.let { repository ->
                        viewModelScope.launch {
                            try { repository.updateDetails(ComicRef.Remote(comicKey), outcome.value) }
                            catch (cancelled: CancellationException) { throw cancelled }
                            catch (_: Exception) { _state.update { it.copy(message = "无法更新书架章节信息。") } }
                        }
                    }
                    when (outcome) {
                        is SourceOutcome.Success -> _state.update { current ->
                            current.copy(
                                status = DetailsStatus.Ready,
                                detail = outcome.value,
                                sourceName = source.name,
                                canOpenTags = canOpenTags,
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
            } catch (_: TimeoutCancellationException) {
                _state.update { it.copy(status = DetailsStatus.Failed, message = "加载超时，请重试。") }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                _state.update { it.copy(status = DetailsStatus.Failed, message = "无法加载漫画详情，请重试。") }
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
    lastUpdated = comic.lastUpdated ?: metadata["Updated"],
    chapters = chapters,
    detail = this,
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

    is SourceRuntimeError.ScriptExecution -> if (
        listOf("not found", "does not exist", "已被删除", "不存在")
            .any(message.lowercase()::contains)
    ) {
        "漫画源暂时无法加载这部作品，请稍后重试。"
    } else {
        "漫画源暂时无法响应。"
    }

    is SourceRuntimeError.InvalidPackage,
    is SourceRuntimeError.InvalidCall,
    is SourceRuntimeError.ScriptSyntax,
    is SourceRuntimeError.Internal,
    -> "漫画源暂时无法响应。"
}
