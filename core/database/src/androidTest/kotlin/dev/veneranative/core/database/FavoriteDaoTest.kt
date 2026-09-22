package dev.veneranative.core.database

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Exercises the shelf queries on real SQLite rather than against a fake.
 *
 * What is worth proving here is what a fake cannot: that the composite primary key collapses a
 * second "add" of the same comic into one row, that the four shelf orders really are SQL `ORDER BY`
 * (including nulls-last for a comic that was never read), and that moving a folder's comics is a
 * single statement the caller can wrap in the folder deletion.
 */
@RunWith(AndroidJUnit4::class)
class FavoriteDaoTest {

    private lateinit var database: VeneraDatabase
    private lateinit var dao: FavoriteDao

    @Before fun setUp() {
        val context: Context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, VeneraDatabase::class.java).build()
        dao = database.favoriteDao()
    }

    @After fun tearDown() {
        database.close()
    }

    private fun folder(
        folderId: String,
        name: String,
        sortOrder: Int,
        removable: Boolean = true,
    ) = FavoriteFolderEntity(
        folderId = folderId,
        name = name,
        sortOrder = sortOrder,
        removable = removable,
    )

    private fun entry(
        refComic: String,
        folderId: String = "folder-1",
        title: String = refComic,
        addedAt: Long = 1_000L,
        lastReadAt: Long? = null,
        chapterCount: Int? = null,
        latestChapterId: String? = null,
        hasUpdate: Boolean = false,
        updatedAt: Long? = null,
    ) = FavoriteEntryEntity(
        refSource = "source-a",
        refComic = refComic,
        folderId = folderId,
        title = title,
        subtitle = null,
        coverRef = null,
        addedAt = addedAt,
        lastReadAt = lastReadAt,
        chapterCount = chapterCount,
        latestChapterId = latestChapterId,
        hasUpdate = hasUpdate,
        updatedAt = updatedAt,
    )

    private suspend fun insertFolders() {
        dao.insertFolder(folder(DEFAULT_FOLDER_ID, DEFAULT_FOLDER_NAME, 0, removable = false))
        dao.insertFolder(folder("folder-1", "Reading", 1))
        dao.insertFolder(folder("folder-2", "Later", 2))
    }

    private suspend fun insert(vararg entries: FavoriteEntryEntity) {
        insertFolders()
        entries.forEach { dao.upsertEntry(it) }
    }

    @Test fun foldersAreObservedInTheirSortOrder() = runTest {
        insertFolders()

        assertEquals(
            listOf(DEFAULT_FOLDER_ID, "folder-1", "folder-2"),
            dao.observeFolders().first().map { it.folderId },
        )
    }

    @Test fun upsertingTheSameComicKeepsOneRow() = runTest {
        insert(entry("comic-1", addedAt = 1_000L))
        dao.upsertEntry(entry("comic-1", addedAt = 5_000L, title = "Renamed"))

        assertEquals(1, dao.entries().size)
        // The DAO stores what it is given; remembering the original "added at" is the repository's
        // job, which is why the repository reads the row before it writes it.
        assertEquals(5_000L, dao.entry("source-a", "comic-1")?.addedAt)
    }

    @Test fun aFolderQueryFiltersAndANullFolderQueryDoesNot() = runTest {
        insert(entry("comic-1", folderId = "folder-1"), entry("comic-2", folderId = "folder-2"))

        assertEquals(listOf("comic-1"), dao.observeByAddedAt("folder-1").first().map { it.refComic })
        assertEquals(
            listOf("comic-2", "comic-1"),
            dao.observeByAddedAt(null).first().map { it.refComic },
        )
    }

    @Test fun eachShelfOrderIsAppliedByTheQuery() = runTest {
        insert(
            entry("b-comic", title = "Beta", addedAt = 1_000L, lastReadAt = 2_000L),
            entry("a-comic", title = "alpha", addedAt = 3_000L, lastReadAt = null),
            entry(
                "c-comic", title = "Charlie", addedAt = 2_000L, lastReadAt = 4_000L,
                hasUpdate = true, updatedAt = 9_000L,
            ),
        )

        assertEquals("a-comic", dao.observeByAddedAt(null).first().first().refComic)
        // NOCASE: "alpha" precedes "Beta" even though 'B' sorts before 'a' byte-wise.
        assertEquals("a-comic", dao.observeByTitle(null).first().first().refComic)
        assertEquals("c-comic", dao.observeByLastRead(null).first().first().refComic)
        assertEquals("c-comic", dao.observeByUpdate(null).first().first().refComic)
        // A comic that was never read sorts last rather than first.
        assertEquals("a-comic", dao.observeByLastRead(null).first().last().refComic)
    }

    @Test fun deletingAFolderMovesItsComicsInsteadOfDroppingThem() = runTest {
        insert(entry("comic-1", folderId = "folder-2"))

        dao.moveEntriesTo(fromFolderId = "folder-2", toFolderId = DEFAULT_FOLDER_ID)
        dao.deleteFolder("folder-2")

        assertNull(dao.folder("folder-2"))
        assertEquals(listOf("comic-1"), dao.observeByAddedAt(DEFAULT_FOLDER_ID).first().map { it.refComic })
    }

    @Test fun clearingAnUpdateKeepsTheChapterSnapshot() = runTest {
        insert(
            entry(
                "comic-1", chapterCount = 10, latestChapterId = "ch-10",
                hasUpdate = true, updatedAt = 5_000L,
            ),
        )

        dao.clearUpdate("source-a", "comic-1")

        val row = requireNotNull(dao.entry("source-a", "comic-1"))
        assertEquals(false, row.hasUpdate)
        assertEquals(10, row.chapterCount)
        assertEquals("ch-10", row.latestChapterId)
    }

    @Test fun removingAComicDropsOnlyThatComic() = runTest {
        insert(entry("comic-1"), entry("comic-2"))

        dao.deleteEntry("source-a", "comic-1")

        assertEquals(listOf("comic-2"), dao.entries().map { it.refComic })
    }

    @Test fun movingAComicChangesOnlyItsFolder() = runTest {
        insert(entry("comic-1", folderId = "folder-1"))

        dao.moveEntry(refSource = "source-a", refComic = "comic-1", folderId = "folder-2")

        assertEquals(emptyList<String>(), dao.observeByAddedAt("folder-1").first().map { it.refComic })
        assertEquals(listOf("comic-1"), dao.observeByAddedAt("folder-2").first().map { it.refComic })
    }
}
