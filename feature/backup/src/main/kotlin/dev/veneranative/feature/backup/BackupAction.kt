package dev.veneranative.feature.backup

sealed interface BackupAction {
    data class PasswordChanged(val value: String) : BackupAction
    data class CategoryToggled(val category: dev.veneranative.core.backup.BackupCategory) : BackupAction
    data object Connect : BackupAction
    data object CreateBackup : BackupAction
    data object Restore : BackupAction
    data object Disconnect : BackupAction
}
