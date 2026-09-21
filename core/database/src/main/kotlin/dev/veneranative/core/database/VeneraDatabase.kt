package dev.veneranative.core.database

import androidx.room.Database
import androidx.room.RoomDatabase

/**
 * The single Room database. Version 1 is the migration baseline: every later schema change is a
 * migration from an exported `schemas/<version>.json`, which is why `exportSchema` stays true.
 */
@Database(
    entities = [ReadingHistoryEntity::class, ReadingProgressEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class VeneraDatabase : RoomDatabase() {

    abstract fun readingHistoryDao(): ReadingHistoryDao

    abstract fun readingProgressDao(): ReadingProgressDao
}
