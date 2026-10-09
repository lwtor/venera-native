package dev.veneranative.core.backup

/** User-selectable logical groups. Downloaded pages and local media are deliberately excluded. */
enum class BackupCategory {
    Favorites,
    ReadingHistory,
    AppPreferences,
}

data class BackupSelection(
    val categories: Set<BackupCategory>,
) {
    init {
        require(categories.isNotEmpty()) { "Select at least one category" }
    }
}

/** Versioned, storage-independent data transfer object. It contains no Android or Room types. */
data class BackupSnapshot(
    val schemaVersion: Int = CURRENT_BACKUP_SCHEMA,
    val createdAtEpochMillis: Long,
    val categories: Set<BackupCategory>,
    val favoriteFolders: List<FavoriteFolderBackup> = emptyList(),
    val favoriteEntries: List<FavoriteEntryBackup> = emptyList(),
    val favoriteMemberships: List<FavoriteMembershipBackup> = emptyList(),
    val readingHistory: List<ReadingHistoryBackup> = emptyList(),
    val readingProgress: List<ReadingProgressBackup> = emptyList(),
    val appPreferences: List<AppPreferenceBackup> = emptyList(),
) {
    init {
        require(schemaVersion == CURRENT_BACKUP_SCHEMA) { "Unsupported backup schema version" }
        require(categories.isNotEmpty()) { "Backup has no selected categories" }
        require(BackupCategory.Favorites in categories ||
            (favoriteFolders.isEmpty() && favoriteEntries.isEmpty() && favoriteMemberships.isEmpty()))
        require(BackupCategory.ReadingHistory in categories ||
            (readingHistory.isEmpty() && readingProgress.isEmpty()))
        require(BackupCategory.AppPreferences in categories || appPreferences.isEmpty())
    }

    companion object {
        const val CURRENT_BACKUP_SCHEMA: Int = 1
    }
}

data class FavoriteFolderBackup(
    val id: String,
    val name: String,
    val sortOrder: Int,
    val removable: Boolean,
)

data class FavoriteEntryBackup(
    val sourceId: String,
    val comicId: String,
    val legacyFolderId: String,
    val title: String,
    val subtitle: String?,
    val coverRef: String?,
    val addedAtEpochMillis: Long,
    val lastReadAtEpochMillis: Long?,
    val chapterCount: Int?,
    val latestChapterId: String?,
    val hasUpdate: Boolean,
    val updatedAtEpochMillis: Long?,
)

data class FavoriteMembershipBackup(
    val sourceId: String,
    val comicId: String,
    val folderId: String,
)

data class ReadingHistoryBackup(
    val sourceId: String,
    val comicId: String,
    val chapterId: String,
    val comicTitle: String,
    val chapterTitle: String,
    val coverUrl: String?,
    val pageIndex: Int,
    val pageCount: Int,
    val updatedAtEpochMillis: Long,
)

data class ReadingProgressBackup(
    val sourceId: String,
    val comicId: String,
    val chapterId: String,
    val pageIndex: Int,
    val updatedAtEpochMillis: Long,
)

data class AppPreferenceBackup(val key: String, val value: String)
