package dev.veneranative.feature.backup

sealed interface BackupAction {
    data class PasswordChanged(val value: String) : BackupAction
    data class CategoryToggled(val category: dev.veneranative.core.backup.BackupCategory) : BackupAction
    data object CreateBackup : BackupAction
    data object RequestRestore : BackupAction
    data object Restore : BackupAction
    data object CancelRestore : BackupAction
}
