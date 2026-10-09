package dev.veneranative.feature.backup

import dev.veneranative.core.backup.BackupCategory
import dev.veneranative.data.backup.GitHubDeviceAuthorization

data class BackupUiState(
    val clientId: String = "",
    val password: String = "",
    val categories: Set<BackupCategory> = setOf(BackupCategory.Favorites, BackupCategory.ReadingHistory),
    val authorization: GitHubDeviceAuthorization? = null,
    val connected: Boolean = false,
    val busy: Boolean = false,
    val message: String? = null,
    val error: String? = null,
)
