package dev.veneranative.data.backup

import kotlinx.coroutines.flow.StateFlow

data class GitHubProfile(val id: Long, val login: String, val name: String?, val avatarUrl: String?) {
    val displayName: String get() = name?.takeIf(String::isNotBlank) ?: login
}

enum class GitHubSyncOperation { Backup, Restore }

data class GitHubSyncRecord(val completedAtEpochMillis: Long, val operation: GitHubSyncOperation)

data class GitHubAccountState(
    val connected: Boolean = false,
    val profile: GitHubProfile? = null,
    val lastSync: GitHubSyncRecord? = null,
)

/** Shared account contract. Features coordinate their own UI without depending on one another. */
interface GitHubAccountService {
    val account: StateFlow<GitHubAccountState>
    suspend fun refreshProfile()
    suspend fun beginAuthorization(): GitHubDeviceAuthorization
    suspend fun pollAuthorization(deviceCode: String): GitHubDevicePollResult
    fun disconnect()
}

interface GitHubBackupService : GitHubAccountService {
    suspend fun createBackup(
        categories: Set<dev.veneranative.core.backup.BackupCategory>, password: CharArray,
    ): dev.veneranative.core.backup.BackupSnapshot
    suspend fun restoreBackup(
        password: CharArray, categories: Set<dev.veneranative.core.backup.BackupCategory>,
        mode: RestoreMode = RestoreMode.Merge,
    ): dev.veneranative.core.backup.BackupSnapshot
}

interface GitHubAccountStore {
    fun read(): GitHubAccountState
    fun write(state: GitHubAccountState)
    fun clear()
}
