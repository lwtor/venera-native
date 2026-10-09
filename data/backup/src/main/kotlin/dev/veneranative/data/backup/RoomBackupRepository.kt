package dev.veneranative.data.backup

import androidx.room.withTransaction
import dev.veneranative.core.backup.AppPreferenceBackup
import dev.veneranative.core.backup.BackupCategory
import dev.veneranative.core.backup.BackupSelection
import dev.veneranative.core.backup.BackupSnapshot
import dev.veneranative.core.backup.FavoriteEntryBackup
import dev.veneranative.core.backup.FavoriteFolderBackup
import dev.veneranative.core.backup.FavoriteMembershipBackup
import dev.veneranative.core.backup.ReadingHistoryBackup
import dev.veneranative.core.backup.ReadingProgressBackup
import dev.veneranative.core.database.FavoriteEntryEntity
import dev.veneranative.core.database.FavoriteFolderEntity
import dev.veneranative.core.database.FavoriteMembershipEntity
import dev.veneranative.core.database.ReadingHistoryEntity
import dev.veneranative.core.database.ReadingProgressEntity
import dev.veneranative.core.database.ScreenPreferenceEntity
import dev.veneranative.core.database.VeneraDatabase

class RoomBackupRepository(
    private val database: VeneraDatabase,
    private val nowEpochMillis: () -> Long = System::currentTimeMillis,
) : BackupRepository {

    override suspend fun createSnapshot(selection: BackupSelection): BackupSnapshot = database.withTransaction {
        val favorites = BackupCategory.Favorites in selection.categories
        val history = BackupCategory.ReadingHistory in selection.categories
        val preferences = BackupCategory.AppPreferences in selection.categories
        BackupSnapshot(
            createdAtEpochMillis = nowEpochMillis(),
            categories = selection.categories,
            favoriteFolders = if (favorites) database.favoriteDao().folders().map(FavoriteFolderEntity::toBackup) else emptyList(),
            favoriteEntries = if (favorites) database.favoriteDao().entries().map(FavoriteEntryEntity::toBackup) else emptyList(),
            favoriteMemberships = if (favorites) database.favoriteDao().memberships().map(FavoriteMembershipEntity::toBackup) else emptyList(),
            readingHistory = if (history) database.readingHistoryDao().all().map(ReadingHistoryEntity::toBackup) else emptyList(),
            readingProgress = if (history) database.readingProgressDao().all().map(ReadingProgressEntity::toBackup) else emptyList(),
            appPreferences = if (preferences) database.screenPreferenceDao().all().map(ScreenPreferenceEntity::toBackup) else emptyList(),
        )
    }

    override suspend fun restore(snapshot: BackupSnapshot, categories: Set<BackupCategory>, mode: RestoreMode) {
        require(categories.isNotEmpty()) { "Select at least one backup category to restore" }
        require(categories.all { it in snapshot.categories }) { "Selected data is not present in this backup" }
        database.withTransaction {
            if (BackupCategory.Favorites in categories) {
                val dao = database.favoriteDao()
                if (mode == RestoreMode.ReplaceSelected) {
                    dao.deleteAllMemberships()
                    dao.deleteAllEntries()
                    dao.deleteAllFolders()
                }
                dao.upsertFolders(snapshot.favoriteFolders.map(FavoriteFolderBackup::toEntity))
                dao.upsertEntries(snapshot.favoriteEntries.map(FavoriteEntryBackup::toEntity))
                dao.insertMemberships(snapshot.favoriteMemberships.map(FavoriteMembershipBackup::toEntity))
            }
            if (BackupCategory.ReadingHistory in categories) {
                val historyDao = database.readingHistoryDao()
                val progressDao = database.readingProgressDao()
                if (mode == RestoreMode.ReplaceSelected) {
                    historyDao.deleteAll()
                    progressDao.deleteAll()
                    historyDao.upsertAll(snapshot.readingHistory.map(ReadingHistoryBackup::toEntity))
                    progressDao.upsertAll(snapshot.readingProgress.map(ReadingProgressBackup::toEntity))
                } else {
                    snapshot.readingHistory.forEach { incoming ->
                        val current = historyDao.find(incoming.sourceId, incoming.comicId)
                        if (current == null || incoming.updatedAtEpochMillis >= current.updatedAtEpochMillis) {
                            historyDao.upsert(incoming.toEntity())
                        }
                    }
                    snapshot.readingProgress.forEach { incoming ->
                        val current = progressDao.find(incoming.sourceId, incoming.comicId)
                        if (current == null || incoming.updatedAtEpochMillis >= current.updatedAtEpochMillis) {
                            progressDao.upsert(incoming.toEntity())
                        }
                    }
                }
            }
            if (BackupCategory.AppPreferences in categories) {
                val dao = database.screenPreferenceDao()
                if (mode == RestoreMode.ReplaceSelected) dao.deleteAll()
                snapshot.appPreferences.forEach { dao.put(it.toEntity()) }
            }
        }
    }
}

private fun FavoriteFolderEntity.toBackup() = FavoriteFolderBackup(folderId, name, sortOrder, removable)
private fun FavoriteFolderBackup.toEntity() = FavoriteFolderEntity(id, name, sortOrder, removable)
private fun FavoriteEntryEntity.toBackup() = FavoriteEntryBackup(
    refSource, refComic, folderId, title, subtitle, coverRef, addedAt, lastReadAt,
    chapterCount, latestChapterId, hasUpdate, updatedAt,
)
private fun FavoriteEntryBackup.toEntity() = FavoriteEntryEntity(
    sourceId, comicId, legacyFolderId, title, subtitle, coverRef, addedAtEpochMillis,
    lastReadAtEpochMillis, chapterCount, latestChapterId, hasUpdate, updatedAtEpochMillis,
)
private fun FavoriteMembershipEntity.toBackup() = FavoriteMembershipBackup(refSource, refComic, folderId)
private fun FavoriteMembershipBackup.toEntity() = FavoriteMembershipEntity(sourceId, comicId, folderId)
private fun ReadingHistoryEntity.toBackup() = ReadingHistoryBackup(
    sourceId, comicId, chapterId, comicTitle, chapterTitle, coverUrl, pageIndex, pageCount, updatedAtEpochMillis,
)
private fun ReadingHistoryBackup.toEntity() = ReadingHistoryEntity(
    sourceId, comicId, chapterId, comicTitle, chapterTitle, coverUrl, pageIndex, pageCount, updatedAtEpochMillis,
)
private fun ReadingProgressEntity.toBackup() = ReadingProgressBackup(
    sourceId, comicId, chapterId, pageIndex, updatedAtEpochMillis,
)
private fun ReadingProgressBackup.toEntity() = ReadingProgressEntity(
    sourceId, comicId, chapterId, pageIndex, updatedAtEpochMillis,
)
private fun ScreenPreferenceEntity.toBackup() = AppPreferenceBackup(key, value)
private fun AppPreferenceBackup.toEntity() = ScreenPreferenceEntity(key, value)
