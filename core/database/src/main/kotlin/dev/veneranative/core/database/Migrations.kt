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

/** S2-02: the download queue — one row per chapter and one per page. */
val MIGRATION_2_3: Migration = object : Migration(2, 3) {

    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `download_task` (" +
                "`task_id` TEXT NOT NULL, " +
                "`ref_source` TEXT NOT NULL, " +
                "`ref_comic` TEXT NOT NULL, " +
                "`ref_chapter` TEXT NOT NULL, " +
                "`title` TEXT NOT NULL, " +
                "`comic_title` TEXT, " +
                "`page_count` INTEGER NOT NULL, " +
                "`completed_pages` INTEGER NOT NULL, " +
                "`state` TEXT NOT NULL, " +
                "`worker_id` TEXT, " +
                "`heartbeat_at` INTEGER NOT NULL, " +
                "`created_at` INTEGER NOT NULL, " +
                "`updated_at` INTEGER NOT NULL, " +
                "PRIMARY KEY(`task_id`)" +
                ")",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_download_task_updated_at` " +
                "ON `download_task` (`updated_at`)",
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `download_page` (" +
                "`task_id` TEXT NOT NULL, " +
                "`page_index` INTEGER NOT NULL, " +
                "`image_ref` TEXT NOT NULL, " +
                "`state` TEXT NOT NULL, " +
                "`relative_path` TEXT, " +
                "`bytes` INTEGER NOT NULL, " +
                "`attempts` INTEGER NOT NULL, " +
                "`last_error` TEXT, " +
                "PRIMARY KEY(`task_id`, `page_index`), " +
                "FOREIGN KEY(`task_id`) REFERENCES `download_task`(`task_id`) " +
                "ON UPDATE NO ACTION ON DELETE CASCADE" +
                ")",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_download_page_state` " +
                "ON `download_page` (`state`)",
        )
    }
}

/** S2-05: SAF grants and imported directory indexes; local_page also reserves archive fields for S2-06. */
val MIGRATION_3_4: Migration = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS local_grant (uri TEXT NOT NULL, kind TEXT NOT NULL, granted_at INTEGER NOT NULL, PRIMARY KEY(uri))")
        db.execSQL("CREATE TABLE IF NOT EXISTS local_comic (comic_id TEXT NOT NULL, title TEXT NOT NULL, kind TEXT NOT NULL, root_uri TEXT NOT NULL, cover_path TEXT, chapter_count INTEGER NOT NULL, added_at INTEGER NOT NULL, PRIMARY KEY(comic_id))")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_local_comic_added_at ON local_comic (added_at)")
        db.execSQL("CREATE TABLE IF NOT EXISTS local_chapter (comic_id TEXT NOT NULL, chapter_id TEXT NOT NULL, title TEXT NOT NULL, sort_index INTEGER NOT NULL, entry_name TEXT, PRIMARY KEY(comic_id, chapter_id))")
        db.execSQL("CREATE TABLE IF NOT EXISTS local_page (comic_id TEXT NOT NULL, chapter_id TEXT NOT NULL, page_index INTEGER NOT NULL, entry_name TEXT NOT NULL, display_name TEXT NOT NULL, size_bytes INTEGER NOT NULL, PRIMARY KEY(comic_id, chapter_id, page_index))")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_local_page_comic_id_chapter_id ON local_page (comic_id, chapter_id)")
    }
}

/** Every migration the database knows about, in order. */
val VENERA_DATABASE_MIGRATIONS: List<Migration> = listOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
