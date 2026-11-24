package dev.veneranative.feature.sources

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.veneranative.core.model.SourceId
import dev.veneranative.data.source.InstallOutcome
import dev.veneranative.data.source.SourceInstallError
import dev.veneranative.data.source.SourceRepository
import dev.veneranative.data.source.SourceCatalogRepository
import dev.veneranative.data.source.SourceCatalogResult
import dev.veneranative.data.settings.ScreenPreferenceRepository
import dev.veneranative.data.comic.ComicCatalog
import dev.veneranative.data.comic.CatalogComicMatch
import dev.veneranative.data.comic.CatalogComicSearchRow
import dev.veneranative.data.comic.CatalogProbeFailureStage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Owns the source list: loading, installing, enabling and removing.
 *
 * Failures become product copy here, not in the data layer. Loading a list of locally stored
 * packages cannot realistically fail, so a load failure means the list is unreadable and the screen
 * offers a retry instead of an empty list — an empty list would claim the user has no sources.
 */
class SourcesViewModel(
    private val repository: SourceRepository,
    private val catalogRepository: SourceCatalogRepository? = null,
    screenPreferences: ScreenPreferenceRepository? = null,
    private val comicCatalog: ComicCatalog? = null,
) : ViewModel() {

    private val _state = MutableStateFlow(SourcesUiState())
    val state: StateFlow<SourcesUiState> = _state.asStateFlow()
    private var screenPreferences = screenPreferences
    private var catalogEdited = false
    private var lastAppliedLocation: String? = null
    private var catalogHistoryLoaded = false
    private var catalogJob: Job? = null
    private var comicSearchJob: Job? = null
    private var configurationGeneration = 0L
    private val preferenceWriteMutex = Mutex()

    init {
        refresh()
        if (catalogRepository != null) {
            if (screenPreferences != null) restoreCatalog() else refreshCatalog()
        }
    }

    fun onAction(action: SourcesAction) {
        when (action) {
            is SourcesAction.InstallLocationChanged ->
                _state.update { it.copy(installLocation = action.value) }

            SourcesAction.Install -> install()

            is SourcesAction.SetEnabled -> setEnabled(action.sourceId, action.enabled)

            is SourcesAction.ReorderSources -> reorder(action.sourceIds)

            is SourcesAction.Uninstall -> uninstall(action.sourceId)

            SourcesAction.Retry -> refresh()

            SourcesAction.DismissMessage -> _state.update { it.copy(message = null) }

            is SourcesAction.CatalogLocationChanged -> {
                catalogEdited = true
                catalogJob?.cancel()
                cancelComicSearch()
                _state.update { it.copy(catalogLocation = action.value, catalogStatus = CatalogStatus.Idle, catalogEntries = emptyList()) }
            }

            is SourcesAction.SelectCatalogLocation -> {
                catalogEdited = true
                catalogJob?.cancel()
                cancelComicSearch()
                _state.update {
                    it.copy(
                        catalogLocation = action.value,
                        catalogStatus = CatalogStatus.Idle,
                        catalogEntries = emptyList(),
                    )
                }
                refreshCatalog()
            }

            SourcesAction.RefreshCatalog -> refreshCatalog()

            is SourcesAction.InstallCatalogEntry ->
                install(
                    location = action.entry.scriptUrl,
                    catalogLocation = action.entry.catalogLocation ?: _state.value.catalogLocation.trim(),
                    expectedSourceId = _state.value.installedSourceFor(action.entry)?.sourceId,
                )

            SourcesAction.UpdateAllOutdated -> updateAllOutdated()

            is SourcesAction.ComicQueryChanged -> {
                comicSearchJob?.cancel()
                _state.update {
                    it.copy(
                        comicQuery = action.query,
                        comicSearchRows = emptyList(),
                        comicSearchLoading = false,
                        comicSearchPerformed = false,
                    )
                }
            }

            SourcesAction.SearchCatalogForComic -> searchCatalogForComic()

            SourcesAction.EditCatalogComicSearch -> {
                comicSearchJob?.cancel()
                _state.update { it.copy(comicSearchPerformed = false, comicSearchLoading = false) }
            }

            SourcesAction.CancelCatalogComicSearch -> cancelComicSearch()

            is SourcesAction.InstallComicMatch -> installComicMatch(action.match)

            is SourcesAction.OpenConfiguration -> openConfiguration(action.sourceId)

            SourcesAction.CloseConfiguration -> {
                configurationGeneration++
                _state.update { it.copy(configuration = null, configurationLoading = false) }
            }

            is SourcesAction.SaveSettings -> saveSettings(action.sourceId, action.values)

            is SourcesAction.InvokeSettingCallback -> invokeSettingCallback(action.sourceId, action.key)

            is SourcesAction.Login -> login(action.sourceId, action.account, action.password)

            is SourcesAction.Relogin -> relogin(action.sourceId)

            is SourcesAction.CheckLogin -> checkLogin(action.sourceId, manual = true)

            is SourcesAction.Logout -> logout(action.sourceId)
        }
    }

    fun attachScreenPreferences(preferences: ScreenPreferenceRepository) {
        if (screenPreferences === preferences) return
        screenPreferences = preferences
        if (lastAppliedLocation != null) restoreHistoryAndPersistCurrent()
        else if (!catalogEdited) restoreCatalog()
    }

    private fun restoreCatalog() {
        viewModelScope.launch {
            val (saved, history) = try {
                val preferences = screenPreferences
                val location = preferences?.get(CATALOG_LOCATION_KEY)?.trim()?.takeIf(String::isNotEmpty)
                val storedHistory = preferences?.get(CATALOG_HISTORY_KEY)
                val history = storedHistory?.let(::decodeCatalogHistory)
                    ?: location?.let(::listOf).orEmpty()
                location to history
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                _state.update { it.copy(message = "无法读取已保存的目录地址，已使用默认地址。") }
                null to emptyList()
            }
            if (catalogEdited) return@launch
            catalogHistoryLoaded = true
            catalogJob?.cancel()
            _state.update {
                it.copy(
                    catalogLocation = saved ?: it.catalogLocation,
                    catalogHistory = history,
                    catalogStatus = CatalogStatus.Idle,
                )
            }
            refreshCatalog()
        }
    }

    private fun restoreHistoryAndPersistCurrent() {
        viewModelScope.launch {
            val savedHistory = try {
                val preferences = screenPreferences
                val stored = preferences?.get(CATALOG_HISTORY_KEY)
                stored?.let(::decodeCatalogHistory)
                    ?: preferences?.get(CATALOG_LOCATION_KEY)?.trim()?.takeIf(String::isNotEmpty)?.let(::listOf).orEmpty()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                emptyList()
            }
            val merged = mergeCatalogHistory(_state.value.catalogHistory, savedHistory)
            _state.update { it.copy(catalogHistory = merged) }
            catalogHistoryLoaded = true
            lastAppliedLocation?.let(::persistCatalog)
        }
    }

    private fun persistCatalog(location: String) {
        viewModelScope.launch {
            preferenceWriteMutex.withLock {
                if (lastAppliedLocation != location) return@withLock
                try {
                    val preferences = screenPreferences ?: return@withLock
                    preferences.put(CATALOG_HISTORY_KEY, encodeCatalogHistory(_state.value.catalogHistory))
                    preferences.put(CATALOG_LOCATION_KEY, location)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    _state.update { it.copy(message = "目录已加载，但地址保存失败，重启后可能无法恢复。") }
                }
            }
        }
    }

    private fun openConfiguration(sourceId: SourceId) {
        val generation = ++configurationGeneration
        _state.update { it.copy(configurationLoading = true, message = null) }
        viewModelScope.launch {
            val configuration = runCatching { repository.configuration(sourceId) }.getOrNull()
            if (generation == configurationGeneration) {
                _state.update {
                    it.copy(
                        configurationLoading = false,
                        configuration = configuration,
                        loginCheckOutcome = null,
                        message = if (configuration == null) "无法读取该漫画源的设置。" else it.message,
                    )
                }
            }
            if (configuration?.supportsLoginValidation == true || configuration?.supportsPasswordLogin == true) {
                checkLogin(sourceId, manual = false)
            }
        }
    }

    private fun reorder(sourceIds: List<SourceId>) {
        if (sourceIds.size != _state.value.sources.size) return
        viewModelScope.launch {
            if (runCatching { repository.reorder(sourceIds) }.getOrDefault(false)) {
                _state.update { current ->
                    val rank = sourceIds.withIndex().associate { it.value to it.index }
                    current.copy(sources = current.sources.sortedBy { rank[it.sourceId] ?: Int.MAX_VALUE })
                }
            }
        }
    }

    private fun saveSettings(sourceId: SourceId, values: Map<String, String>) {
        if (_state.value.settingsSaving) return
        _state.update { it.copy(settingsSaving = true, message = null) }
        viewModelScope.launch {
            val saved = runCatching { repository.updateSettings(sourceId, values) }.getOrDefault(false)
            val configuration = if (saved) runCatching { repository.configuration(sourceId) }.getOrNull() else null
            _state.update {
                it.copy(
                    settingsSaving = false,
                    configuration = configuration ?: it.configuration,
                    message = if (saved) "设置已保存；如账号未登录，请重新登录。" else "设置保存失败，请检查输入后重试。",
                )
            }
        }
    }

    private fun invokeSettingCallback(sourceId: SourceId, key: String) {
        if (_state.value.accountActionInProgress) return
        _state.update { it.copy(accountActionInProgress = true, loginCheckOutcome = null, message = null) }
        viewModelScope.launch {
            val succeeded = runCatching { repository.invokeSettingCallback(sourceId, key) }.getOrDefault(false)
            _state.update { it.copy(accountActionInProgress = false, message = if (succeeded) "操作已完成。" else "来源设置操作失败。") }
        }
    }

    private fun login(sourceId: SourceId, account: String, password: String) {
        if (_state.value.accountActionInProgress) return
        _state.update { it.copy(accountActionInProgress = true, loginCheckOutcome = null, message = null) }
        viewModelScope.launch {
            val result = runCatching { repository.login(sourceId, account, password) }
                .getOrDefault(dev.veneranative.data.source.SourceLoginOutcome.Failed)
            val configuration = runCatching { repository.configuration(sourceId) }.getOrNull()
            val message = when (result) {
                dev.veneranative.data.source.SourceLoginOutcome.Success -> "登录成功。"
                dev.veneranative.data.source.SourceLoginOutcome.SuccessCredentialsNotSaved -> "登录成功，但无法安全保存账号信息；下次需要手动登录。"
                dev.veneranative.data.source.SourceLoginOutcome.Unsupported -> "该来源不支持账号密码登录。"
                dev.veneranative.data.source.SourceLoginOutcome.Failed -> "登录失败，请检查账号密码或网络。"
            }
            _state.update { it.copy(accountActionInProgress = false, configuration = configuration ?: it.configuration, message = message) }
            if (result == dev.veneranative.data.source.SourceLoginOutcome.Success ||
                result == dev.veneranative.data.source.SourceLoginOutcome.SuccessCredentialsNotSaved
            ) checkLogin(sourceId, manual = false)
        }
    }

    private fun relogin(sourceId: SourceId) {
        if (_state.value.accountActionInProgress) return
        _state.update { it.copy(accountActionInProgress = true, message = null) }
        viewModelScope.launch {
            val result = runCatching { repository.relogin(sourceId) }
                .getOrDefault(dev.veneranative.data.source.SourceLoginOutcome.Failed)
            val configuration = runCatching { repository.configuration(sourceId) }.getOrNull()
            val message = when (result) {
                dev.veneranative.data.source.SourceLoginOutcome.Success -> "已使用保存的账号信息重新登录。"
                dev.veneranative.data.source.SourceLoginOutcome.SuccessCredentialsNotSaved -> "已使用保存的账号信息重新登录。"
                dev.veneranative.data.source.SourceLoginOutcome.Unsupported -> "该来源不支持账号密码登录。"
                dev.veneranative.data.source.SourceLoginOutcome.Failed -> "自动登录失败，请检查网络或更新保存的账号信息。"
            }
            _state.update { it.copy(accountActionInProgress = false, configuration = configuration ?: it.configuration, message = message) }
            if (result == dev.veneranative.data.source.SourceLoginOutcome.Success) checkLogin(sourceId, manual = false)
        }
    }

    private fun checkLogin(sourceId: SourceId, manual: Boolean) {
        if (sourceId in _state.value.loginCheckInProgressSourceIds) {
            if (manual) _state.update { it.copy(manualLoginCheckSourceIds = it.manualLoginCheckSourceIds + sourceId) }
            return
        }
        _state.update { current -> current.copy(
            loginCheckInProgressSourceIds = current.loginCheckInProgressSourceIds + sourceId,
            manualLoginCheckSourceIds = if (manual) current.manualLoginCheckSourceIds + sourceId else current.manualLoginCheckSourceIds,
            loginCheckOutcomes = current.loginCheckOutcomes - sourceId,
            loginCheckOutcome = null,
        ) }
        viewModelScope.launch {
            val result = runCatching { repository.checkLogin(sourceId) }
                .getOrDefault(dev.veneranative.data.source.SourceLoginCheckOutcome.Failed)
            _state.update { current -> current.copy(
                loginCheckInProgressSourceIds = current.loginCheckInProgressSourceIds - sourceId,
                manualLoginCheckSourceIds = current.manualLoginCheckSourceIds - sourceId,
                loginCheckOutcomes = current.loginCheckOutcomes + (sourceId to result),
                loginCheckOutcome = result,
            ) }
        }
    }

    private fun logout(sourceId: SourceId) {
        if (_state.value.accountActionInProgress) return
        _state.update { it.copy(accountActionInProgress = true, message = null) }
        viewModelScope.launch {
            val result = runCatching { repository.logout(sourceId) }
                .getOrDefault(dev.veneranative.data.source.SourceLoginOutcome.Failed)
            val configuration = runCatching { repository.configuration(sourceId) }.getOrNull()
            val message = when (result) {
                dev.veneranative.data.source.SourceLoginOutcome.Success -> "已退出登录。"
                dev.veneranative.data.source.SourceLoginOutcome.SuccessCredentialsNotSaved -> "已退出登录。"
                dev.veneranative.data.source.SourceLoginOutcome.Unsupported -> "该来源不支持退出登录。"
                dev.veneranative.data.source.SourceLoginOutcome.Failed -> "退出登录失败。"
            }
            _state.update { it.copy(accountActionInProgress = false, configuration = configuration ?: it.configuration, message = message) }
        }
    }

    private fun refreshCatalog() {
        cancelComicSearch()
        val source = catalogRepository ?: return
        val location = _state.value.catalogLocation.trim()
        if (location.isEmpty() || _state.value.catalogStatus == CatalogStatus.Loading) return
        _state.update { it.copy(catalogStatus = CatalogStatus.Loading) }
        catalogJob = viewModelScope.launch {
            when (val result = source.load(location)) {
                is SourceCatalogResult.Success -> {
                    val history = rememberCatalogLocation(_state.value.catalogHistory, location)
                    lastAppliedLocation = location
                    catalogEdited = false
                    _state.update {
                        it.copy(
                            catalogEntries = result.entries,
                            catalogStatus = CatalogStatus.Ready,
                            catalogHistory = history,
                        )
                    }
                    if (catalogHistoryLoaded || screenPreferences != null) persistCatalog(location)
                }
                is SourceCatalogResult.Failure -> _state.update {
                    it.copy(catalogStatus = CatalogStatus.Failed)
                }
            }
        }
    }

    private fun cancelComicSearch() {
        comicSearchJob?.cancel()
        comicSearchJob = null
        _state.update {
            it.copy(
                comicSearchLoading = false,
                comicSearchRows = it.comicSearchRows.map { row ->
                    if (row.isLoading) row.copy(isLoading = false, isCancelled = true) else row
                },
            )
        }
    }

    private fun refresh() {
        _state.update { it.copy(status = SourcesStatus.Loading, message = null) }
        viewModelScope.launch {
            runCatching { repository.installed() }
                .onSuccess { sources ->
                    _state.update { it.copy(status = SourcesStatus.Ready, sources = sources) }
                }
                .onFailure {
                    _state.update {
                        it.copy(status = SourcesStatus.Failed, message = "无法读取漫画源列表。")
                    }
                }
        }
    }

    private fun install() {
        val location = _state.value.installLocation.trim()
        install(location)
    }

    private fun install(
        location: String,
        catalogLocation: String? = null,
        expectedSourceId: SourceId? = null,
    ) {
        if (location.isEmpty() || _state.value.installing) return

        val existing = _state.value.sources.firstOrNull {
            it.sourceId == expectedSourceId || (expectedSourceId == null && it.origin == location)
        }
        val switchingCatalog = existing != null && catalogLocation != null && existing.catalogLocation != catalogLocation
        _state.update { it.copy(installing = true, message = null) }
        viewModelScope.launch {
            val outcome = if (catalogLocation != null) {
                repository.installFromCatalog(location, catalogLocation)
            } else {
                repository.install(location)
            }
            val sources = runCatching { repository.installed() }.getOrDefault(_state.value.sources)
            _state.update { current ->
                when (outcome) {
                    is InstallOutcome.Success -> current.copy(
                        installing = false,
                        sources = sources,
                        status = SourcesStatus.Ready,
                        installLocation = if (current.installLocation.trim() == location) "" else current.installLocation,
                        message = if (switchingCatalog) {
                            "${outcome.installed.name} 已切换到此目录。"
                        } else if (existing != null) {
                            "${outcome.installed.name} 已更新至版本 ${outcome.installed.version}。"
                        } else {
                            "${outcome.installed.name} 已安装。"
                        },
                    )

                    is InstallOutcome.Failure -> current.copy(
                        installing = false,
                        sources = sources,
                        status = SourcesStatus.Ready,
                        message = outcome.error.toMessage(),
                    )
                }
            }
        }
    }

    /**
     * Updates every installed source that has a newer catalog version, one at a time.
     *
     * Reuses the same catalog/install path as a single update so signature and catalog switching
     * behave identically. The whole batch is one busy window; a failure does not abort the rest.
     */
    private fun updateAllOutdated() {
        if (_state.value.installing) return
        val targets = _state.value.sources.mapNotNull { source ->
            val entry = _state.value.catalogUpdateFor(source) ?: return@mapNotNull null
            Triple(entry, entry.catalogLocation ?: _state.value.catalogLocation.trim(), source.sourceId)
        }
        if (targets.isEmpty()) return
        _state.update { it.copy(installing = true, message = null) }
        viewModelScope.launch {
            var updated = 0
            var failed = 0
            for ((entry, catalogLocation, sourceId) in targets) {
                val outcome = runCatching { repository.installFromCatalog(entry.scriptUrl, catalogLocation) }.getOrNull()
                if (outcome is InstallOutcome.Success) updated++ else failed++
            }
            val sources = runCatching { repository.installed() }.getOrDefault(_state.value.sources)
            _state.update { current ->
                current.copy(
                    installing = false,
                    sources = sources,
                    status = SourcesStatus.Ready,
                    message = buildString {
                        if (updated > 0) append("已更新 $updated 个来源。")
                        if (failed > 0) append("${if (updated > 0) " " else ""}$failed 个更新失败，请重试。")
                    },
                )
            }
        }
    }

    private fun searchCatalogForComic() {
        val query = _state.value.comicQuery.trim()
        val comicCatalog = comicCatalog ?: return
        if (query.isBlank() || _state.value.catalogStatus != CatalogStatus.Ready) return
        comicSearchJob?.cancel()
        val entries = _state.value.catalogEntries
        _state.update {
            it.copy(
                comicSearchLoading = true,
                comicSearchRows = entries.map { CatalogComicSearchRow(it) },
                comicSearchPerformed = true,
                message = null,
            )
        }
        comicSearchJob = viewModelScope.launch {
            val report = try {
                comicCatalog.searchCatalog(
                    query = query,
                    entries = entries,
                    onSourceResult = { result ->
                        _state.update { current ->
                            if (current.comicQuery.trim() != query) current
                            else current.copy(comicSearchRows = current.comicSearchRows.replaceSearchRow(result))
                        }
                    },
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                dev.veneranative.data.comic.CatalogComicSearchReport(
                    rows = entries.map {
                        CatalogComicSearchRow(it, isLoading = false, failureStage = CatalogProbeFailureStage.Search)
                    },
                )
            }
            _state.update { current ->
                if (current.comicQuery.trim() != query) current
                else current.copy(
                    comicSearchLoading = false,
                    comicSearchRows = report.rows,
                )
            }
        }
    }

    private fun List<CatalogComicSearchRow>.replaceSearchRow(result: CatalogComicSearchRow): List<CatalogComicSearchRow> =
        map { row ->
            if (row.source.scriptUrl == result.source.scriptUrl && row.source.catalogLocation == result.source.catalogLocation) result else row
        }

    private fun installComicMatch(match: CatalogComicMatch) {
        val entry = match.source
        if (entry.catalogLocation == null || _state.value.installing) return
        val installed = _state.value.sources.firstOrNull { it.sourceId == match.comic.key.sourceId }
        if (installed != null && installed.catalogLocation == entry.catalogLocation && installed.version == entry.version) {
            _state.update { it.copy(message = "此漫画源已安装。请从已安装列表打开或切换来源。") }
            return
        }
        install(entry.scriptUrl, entry.catalogLocation, expectedSourceId = match.comic.key.sourceId)
    }

    private fun setEnabled(sourceId: SourceId, enabled: Boolean) {
        if (sourceId in _state.value.busySourceIds) return

        _state.update { it.copy(busySourceIds = it.busySourceIds + sourceId, message = null) }
        viewModelScope.launch {
            val changed = repository.setEnabled(sourceId, enabled)
            val sources = runCatching { repository.installed() }.getOrDefault(_state.value.sources)
            _state.update { current ->
                current.copy(
                    busySourceIds = current.busySourceIds - sourceId,
                    sources = sources,
                    message = if (changed) null else "此漫画源已不在已安装列表中。",
                )
            }
        }
    }

    private fun uninstall(sourceId: SourceId) {
        if (sourceId in _state.value.busySourceIds) return

        _state.update { it.copy(busySourceIds = it.busySourceIds + sourceId, message = null) }
        viewModelScope.launch {
            val removed = repository.uninstall(sourceId)
            val sources = runCatching { repository.installed() }.getOrDefault(_state.value.sources)
            _state.update { current ->
                current.copy(
                    busySourceIds = current.busySourceIds - sourceId,
                    sources = sources,
                    message = if (removed) "漫画源已移除。" else "此漫画源已不在已安装列表中。",
                )
            }
        }
    }
}

private const val CATALOG_LOCATION_KEY = "sources.catalog.location"
private const val CATALOG_HISTORY_KEY = "sources.catalog.history"
private const val MAX_CATALOG_HISTORY = 10

private fun rememberCatalogLocation(history: List<String>, location: String): List<String> =
    (listOf(location.trim()) + history.filterNot { it.trim() == location.trim() })
        .filter(String::isNotEmpty)
        .take(MAX_CATALOG_HISTORY)

private fun mergeCatalogHistory(primary: List<String>, secondary: List<String>): List<String> =
    (primary + secondary).distinct().take(MAX_CATALOG_HISTORY)

private fun encodeCatalogHistory(history: List<String>): String =
    JsonArray(history.map(::JsonPrimitive)).toString()

private fun decodeCatalogHistory(encoded: String): List<String> = runCatching {
    Json.parseToJsonElement(encoded).jsonArray.mapNotNull { it.jsonPrimitive.contentOrNull }
        .map(String::trim)
        .filter(String::isNotEmpty)
        .distinct()
        .take(MAX_CATALOG_HISTORY)
}.getOrDefault(emptyList())

/**
 * Maps a domain error to product copy.
 *
 * Each case exists because the user can do something different about it, which is also why this is
 * not a single "install failed" string.
 */
private fun SourceInstallError.toMessage(): String = when (this) {
    SourceInstallError.LocationUnreadable -> "该位置没有可用的漫画源脚本。"
    SourceInstallError.InvalidMetadata -> "此脚本未声明有效的漫画源。"
    SourceInstallError.EngineUnavailable -> "此设备暂时无法运行漫画源。"
    SourceInstallError.Rejected -> "漫画源校验未通过，未安装。"
    SourceInstallError.StorageFailed -> "无法将漫画源保存到此设备。"
}
