package dev.veneranative.feature.backup

import dev.veneranative.core.backup.BackupCategory

data class BackupUiState(
    val password: String = "",
    val categories: Set<BackupCategory> = setOf(BackupCategory.Favorites, BackupCategory.ReadingHistory),
    val connected: Boolean = false,
    val busy: Boolean = false,
    val restoring: Boolean = false,
    val confirmRestore: Boolean = false,
    val message: String? = null,
    val error: String? = null,
) {
    val canSubmit: Boolean get() = connected && !busy && password.length >= 8 && categories.isNotEmpty()
}
