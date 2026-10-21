package dev.veneranative.feature.sources

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.veneranative.core.model.SourceId
import dev.veneranative.data.source.InstallOutcome
import dev.veneranative.data.source.SourceInstallError
import dev.veneranative.data.source.SourceRepository
import dev.veneranative.data.source.SourceCatalogRepository
import dev.veneranative.data.source.SourceCatalogResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

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
) : ViewModel() {

    private val _state = MutableStateFlow(SourcesUiState())
    val state: StateFlow<SourcesUiState> = _state.asStateFlow()

    init {
        refresh()
        if (catalogRepository != null) refreshCatalog()
    }

    fun onAction(action: SourcesAction) {
        when (action) {
            is SourcesAction.InstallLocationChanged ->
                _state.update { it.copy(installLocation = action.value) }

            SourcesAction.Install -> install()

            is SourcesAction.SetEnabled -> setEnabled(action.sourceId, action.enabled)

            is SourcesAction.Uninstall -> uninstall(action.sourceId)

            SourcesAction.Retry -> refresh()

            SourcesAction.DismissMessage -> _state.update { it.copy(message = null) }

            is SourcesAction.CatalogLocationChanged -> _state.update { it.copy(catalogLocation = action.value) }

            SourcesAction.RefreshCatalog -> refreshCatalog()

            is SourcesAction.InstallCatalogEntry -> install(action.entry.scriptUrl)

            is SourcesAction.OpenConfiguration -> openConfiguration(action.sourceId)

            SourcesAction.CloseConfiguration -> _state.update { it.copy(configuration = null) }

            is SourcesAction.SaveSettings -> saveSettings(action.sourceId, action.values)

            is SourcesAction.InvokeSettingCallback -> invokeSettingCallback(action.sourceId, action.key)

            is SourcesAction.Login -> login(action.sourceId, action.account, action.password)

            is SourcesAction.Logout -> logout(action.sourceId)
        }
    }

    private fun openConfiguration(sourceId: SourceId) {
        if (_state.value.configurationLoading) return
        _state.update { it.copy(configurationLoading = true, message = null) }
        viewModelScope.launch {
            val configuration = runCatching { repository.configuration(sourceId) }.getOrNull()
            _state.update {
                it.copy(
                    configurationLoading = false,
                    configuration = configuration,
                    message = if (configuration == null) "无法读取该漫画源的设置。" else it.message,
                )
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
        _state.update { it.copy(accountActionInProgress = true, message = null) }
        viewModelScope.launch {
            val succeeded = runCatching { repository.invokeSettingCallback(sourceId, key) }.getOrDefault(false)
            _state.update { it.copy(accountActionInProgress = false, message = if (succeeded) "操作已完成。" else "来源设置操作失败。") }
        }
    }

    private fun login(sourceId: SourceId, account: String, password: String) {
        if (_state.value.accountActionInProgress) return
        _state.update { it.copy(accountActionInProgress = true, message = null) }
        viewModelScope.launch {
            val result = runCatching { repository.login(sourceId, account, password) }
                .getOrDefault(dev.veneranative.data.source.SourceLoginOutcome.Failed)
            val configuration = runCatching { repository.configuration(sourceId) }.getOrNull()
            val message = when (result) {
                dev.veneranative.data.source.SourceLoginOutcome.Success -> "登录成功。"
                dev.veneranative.data.source.SourceLoginOutcome.Unsupported -> "该来源不支持账号密码登录。"
                dev.veneranative.data.source.SourceLoginOutcome.Failed -> "登录失败，请检查账号密码或网络。"
            }
            _state.update { it.copy(accountActionInProgress = false, configuration = configuration ?: it.configuration, message = message) }
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
                dev.veneranative.data.source.SourceLoginOutcome.Unsupported -> "该来源不支持退出登录。"
                dev.veneranative.data.source.SourceLoginOutcome.Failed -> "退出登录失败。"
            }
            _state.update { it.copy(accountActionInProgress = false, configuration = configuration ?: it.configuration, message = message) }
        }
    }

    private fun refreshCatalog() {
        val source = catalogRepository ?: return
        val location = _state.value.catalogLocation.trim()
        if (location.isEmpty() || _state.value.catalogStatus == CatalogStatus.Loading) return
        _state.update { it.copy(catalogStatus = CatalogStatus.Loading) }
        viewModelScope.launch {
            when (val result = source.load(location)) {
                is SourceCatalogResult.Success -> _state.update {
                    it.copy(catalogEntries = result.entries, catalogStatus = CatalogStatus.Ready)
                }
                is SourceCatalogResult.Failure -> _state.update {
                    it.copy(catalogStatus = CatalogStatus.Failed)
                }
            }
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

    private fun install(location: String) {
        if (location.isEmpty() || _state.value.installing) return

        _state.update { it.copy(installing = true, message = null) }
        viewModelScope.launch {
            val outcome = repository.install(location)
            val sources = runCatching { repository.installed() }.getOrDefault(_state.value.sources)
            _state.update { current ->
                when (outcome) {
                    is InstallOutcome.Success -> current.copy(
                        installing = false,
                        sources = sources,
                        status = SourcesStatus.Ready,
                        installLocation = if (current.installLocation.trim() == location) "" else current.installLocation,
                        message = "${outcome.installed.name} 已安装。",
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
