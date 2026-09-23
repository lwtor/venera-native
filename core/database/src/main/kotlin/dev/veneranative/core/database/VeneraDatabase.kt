package dev.veneranative.core.database

import androidx.room.Database
import androidx.room.RoomDatabase

/**
 * The single Room database.
 *
 * Each version is a migration from an exported `schemas/<version>.json`, which is why
 * `exportSchema` stays true: the exported files are the baseline `MigrationTestHelper` validates
 * against, and a version bump without one is a database Room silently cannot open on upgrade.
 *
 * Version 2 adds the shelf (S2-01) and version 3 the download queue (S2-02). Later stages add their
 * own step rather than editing an existing one.
 */
@Database(
    entities = [
        ReadingHistoryEntity::class,
        ReadingProgressEntity::class,
        FavoriteFolderEntity::class,
        FavoriteEntryEntity::class,
        DownloadTaskEntity::class,
        DownloadPageEntity::class,
    ],
    version = 3,
    exportSchema = true,
)
abstract class VeneraDatabase : RoomDatabase() {

    abstract fun readingHistoryDao(): ReadingHistoryDao

    abstract fun readingProgressDao(): ReadingProgressDao

    abstract fun favoriteDao(): FavoriteDao

    abstract fun downloadDao(): DownloadDao
}
