package dev.veneranative.core.database

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Guards the exported schema, which is the baseline every future migration is written against.
 *
 * Version 1 has no migrations yet, so this does not prove a migration succeeded — it proves the
 * exported `1.json` really describes what the entities create. If someone edits an entity and
 * forgets to add a migration with a new schema export, this fails instead of shipping a database
 * that Room cannot open.
 */
@RunWith(AndroidJUnit4::class)
class VeneraDatabaseMigrationTest {

    private companion object {
        const val TEST_DB = "venera-migration-test"
    }

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        VeneraDatabase::class.java,
        listOf(),
        FrameworkSQLiteOpenHelperFactory(),
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
}
