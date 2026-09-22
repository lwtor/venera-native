package dev.veneranative.core.database

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Schema changes, one object per step.
 *
 * Each step is a separate migration so a task can ship its own schema and its own exported
 * `schemas/<version>.json`: `@Database(version)` and the entity list are global, and jumping
 * several versions at once would put next task's empty tables into this task's commit.
 */

/** Seeds the folder comics fall back to, both in the migration and on a fresh install. */
internal const val INSERT_DEFAULT_FOLDER: String =
    "INSERT OR IGNORE INTO `favorite_folder` (`folder_id`, `name`, `sort_order`, `removable`) " +
        "VALUES ('$DEFAULT_FOLDER_ID', '$DEFAULT_FOLDER_NAME', 0, 0)"

/** S2-01: the shelf — folders and the comics in them. */
val MIGRATION_1_2: Migration = object : Migration(1, 2) {

    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `favorite_folder` (" +
                "`folder_id` TEXT NOT NULL, " +
                "`name` TEXT NOT NULL, " +
                "`sort_order` INTEGER NOT NULL, " +
                "`removable` INTEGER NOT NULL, " +
                "PRIMARY KEY(`folder_id`)" +
                ")",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_favorite_folder_sort_order` " +
                "ON `favorite_folder` (`sort_order`)",
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `favorite_entry` (" +
                "`ref_source` TEXT NOT NULL, " +
                "`ref_comic` TEXT NOT NULL, " +
                "`folder_id` TEXT NOT NULL, " +
                "`title` TEXT NOT NULL, " +
                "`subtitle` TEXT, " +
                "`cover_ref` TEXT, " +
                "`added_at` INTEGER NOT NULL, " +
                "`last_read_at` INTEGER, " +
                "`chapter_count` INTEGER, " +
                "`latest_chapter_id` TEXT, " +
                "`has_update` INTEGER NOT NULL, " +
                "`updated_at` INTEGER, " +
                "PRIMARY KEY(`ref_source`, `ref_comic`)" +
                ")",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_favorite_entry_folder_id` " +
                "ON `favorite_entry` (`folder_id`)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_favorite_entry_added_at` " +
                "ON `favorite_entry` (`added_at`)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_favorite_entry_has_update` " +
                "ON `favorite_entry` (`has_update`)",
        )
        db.execSQL(INSERT_DEFAULT_FOLDER)
    }
}

/** Every migration the database knows about, in order. */
val VENERA_DATABASE_MIGRATIONS: List<Migration> = listOf(MIGRATION_1_2)
