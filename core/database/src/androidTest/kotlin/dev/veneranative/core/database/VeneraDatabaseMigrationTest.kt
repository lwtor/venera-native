package dev.veneranative.core.database

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Guards the exported schema, which is the baseline every future migration is written against, and
 * the migrations themselves.
 *
 * "The migration did not crash" is not enough: a step that recreates a table instead of altering it
 * passes that bar while quietly dropping everything the user had. So every step also has a case
 * that writes rows in the *old* schema and asserts they survive into the new one.
 */
@RunWith(AndroidJUnit4::class)
class VeneraDatabaseMigrationTest {

    private companion object {
        const val TEST_DB = "venera-migration-test"
    }

    // Room 2.8 takes the migrations per call, not on the helper: a test should state which step it
    // is exercising instead of sharing one list across every case.
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        VeneraDatabase::class.java,
    )

    @Test fun theExportedSchemaMatchesWhatRoomCreates() {
        val created = helper.createDatabase(TEST_DB, 1)

        val tables = created.query(
            "SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%' " +
                "AND name NOT LIKE 'android_%' ORDER BY name",
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(cursor.getString(0))
            }
        }
        created.close()

        assertEquals(listOf("reading_history", "reading_progress"), tables)
    }

    @Test fun openingVersion1FromTheExportedSchemaValidates() {
        helper.createDatabase(TEST_DB, 1).close()

        helper.runMigrationsAndValidate(TEST_DB, 1, true).close()
    }

    @Test fun versionTwoCreatesTheShelfTables() {
        val created = helper.createDatabase(TEST_DB, 2)

        assertEquals(
            listOf("favorite_entry", "favorite_folder", "reading_history", "reading_progress"),
            created.tableNames(),
        )
        created.close()
    }

    @Test fun migratingFromOneToTwoValidatesAgainstTheExportedSchema() {
        helper.createDatabase(TEST_DB, 1).close()

        helper.runMigrationsAndValidate(TEST_DB, 2, true, MIGRATION_1_2).close()
    }

    @Test fun migratingFromOneToTwoKeepsStageOneRows() {
        helper.createDatabase(TEST_DB, 1).apply {
            execSQL(
                "INSERT INTO reading_history VALUES(" +
                    "'source-a','comic-1','chapter-3','Comic','Chapter 3','cover',3,12,1000)",
            )
            execSQL("INSERT INTO reading_progress VALUES('source-a','comic-1','chapter-3',3,1000)")
            close()
        }

        helper.runMigrationsAndValidate(TEST_DB, 2, true, MIGRATION_1_2).use { db ->
            assertEquals(listOf("chapter-3" to 3), db.readPairs("reading_history"))
            assertEquals(listOf("chapter-3" to 3), db.readPairs("reading_progress"))
        }
    }

    @Test fun migratingFromOneToTwoSeedsTheDefaultFolder() {
        helper.createDatabase(TEST_DB, 1).close()

        helper.runMigrationsAndValidate(TEST_DB, 2, true, MIGRATION_1_2).use { db ->
            val folders = db.query("SELECT folder_id, removable FROM favorite_folder").use { cursor ->
                buildList {
                    while (cursor.moveToNext()) add(cursor.getString(0) to cursor.getInt(1))
                }
            }

            assertEquals(listOf(DEFAULT_FOLDER_ID to 0), folders)
        }
    }

    private fun SupportSQLiteDatabase.readPairs(table: String): List<Pair<String, Int>> =
        query("SELECT chapter_id, page_index FROM $table").use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(cursor.getString(0) to cursor.getInt(1))
            }
        }

    private fun SupportSQLiteDatabase.tableNames(): List<String> = query(
        "SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%' " +
            "AND name NOT LIKE 'android_%' ORDER BY name",
    ).use { cursor ->
        buildList {
            while (cursor.moveToNext()) add(cursor.getString(0))
        }
    }
}
