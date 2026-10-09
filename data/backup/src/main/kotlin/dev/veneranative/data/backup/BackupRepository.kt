package dev.veneranative.data.backup

import dev.veneranative.core.backup.BackupCategory
import dev.veneranative.core.backup.BackupSelection
import dev.veneranative.core.backup.BackupSnapshot

/** Creates and restores selected logical data. Binary downloads and caches are never included. */
interface BackupRepository {
    suspend fun createSnapshot(selection: BackupSelection): BackupSnapshot

    suspend fun restore(
        snapshot: BackupSnapshot,
        categories: Set<BackupCategory> = snapshot.categories,
        mode: RestoreMode = RestoreMode.Merge,
    )
}

enum class RestoreMode { Merge, ReplaceSelected }
