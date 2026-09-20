package dev.veneranative.feature.sources

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.veneranative.core.model.SourceId
import dev.veneranative.data.source.InstallOutcome
import dev.veneranative.data.source.SourceInstallError
import dev.veneranative.data.source.SourceRepository
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
) : ViewModel() {

    private val _state = MutableStateFlow(SourcesUiState())
    val state: StateFlow<SourcesUiState> = _state.asStateFlow()

    init {
        refresh()
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
                        it.copy(status = SourcesStatus.Failed, message = "The source list could not be read.")
                    }
                }
        }
    }

    private fun install() {
        val location = _state.value.installLocation.trim()
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
                        installLocation = "",
                        message = "${outcome.installed.name} installed.",
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
                    message = if (changed) null else "That source is no longer installed.",
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
                    message = if (removed) "Source removed." else "That source is no longer installed.",
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
    SourceInstallError.LocationUnreadable -> "No source script was found at that location."
    SourceInstallError.InvalidMetadata -> "That script does not declare a usable source."
    SourceInstallError.EngineUnavailable -> "Comic sources are unavailable on this device."
    SourceInstallError.Rejected -> "The source was rejected and was not installed."
    SourceInstallError.StorageFailed -> "The source could not be saved on this device."
}
