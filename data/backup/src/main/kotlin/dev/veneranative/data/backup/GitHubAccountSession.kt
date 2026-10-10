package dev.veneranative.data.backup

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Keeps one observable account and isolates successful receipts when the GitHub identity changes. */
class GitHubAccountSession(
    private val store: GitHubAccountStore,
    authorized: Boolean,
    private val nowEpochMillis: () -> Long = System::currentTimeMillis,
) {
    private val mutableState = MutableStateFlow(
        if (authorized) store.read().copy(connected = true) else GitHubAccountState(),
    )
    val state = mutableState.asStateFlow()

    @Synchronized
    fun authorized() {
        store.clear()
        mutableState.value = GitHubAccountState(connected = true)
    }

    @Synchronized
    fun updateProfile(profile: GitHubProfile) {
        val previous = mutableState.value
        if (!previous.connected) return
        val next = previous.copy(profile = profile, lastSync = previous.lastSync.takeIf { previous.profile?.id == profile.id })
        store.write(next)
        mutableState.value = next
    }

    @Synchronized
    fun completed(operation: GitHubSyncOperation, accountId: Long) {
        val current = mutableState.value
        if (!current.connected || current.profile?.id != accountId) return
        val next = current.copy(lastSync = GitHubSyncRecord(nowEpochMillis(), operation))
        store.write(next)
        mutableState.value = next
    }

    @Synchronized
    fun disconnect() {
        store.clear()
        mutableState.value = GitHubAccountState()
    }
}
