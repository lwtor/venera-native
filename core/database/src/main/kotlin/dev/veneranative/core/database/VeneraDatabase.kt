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
 * Version 2 adds the shelf, version 3 the download queue, and version 4 the local library (S2-05).
 * Each task adds one migration rather than editing an existing step.
 */
@Database(
    entities = [
        ReadingHistoryEntity::class,
        ReadingProgressEntity::class,
        FavoriteFolderEntity::class,
        FavoriteEntryEntity::class,
        DownloadTaskEntity::class,
        DownloadPageEntity::class,
        LocalGrantEntity::class,
        LocalComicEntity::class,
        LocalChapterEntity::class,
        LocalPageEntity::class,
    ],
    version = 4,
    exportSchema = true,
)
abstract class VeneraDatabase : RoomDatabase() {

    abstract fun readingHistoryDao(): ReadingHistoryDao

    abstract fun readingProgressDao(): ReadingProgressDao

    abstract fun favoriteDao(): FavoriteDao

    abstract fun downloadDao(): DownloadDao

    abstract fun localDao(): LocalDao
}
