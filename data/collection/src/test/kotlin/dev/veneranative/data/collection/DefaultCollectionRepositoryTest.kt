package dev.veneranative.data.collection

import dev.veneranative.core.database.DEFAULT_FOLDER_ID
import dev.veneranative.core.database.FavoriteEntryEntity
import dev.veneranative.core.database.FavoriteFolderEntity
import dev.veneranative.core.model.ComicKey
import dev.veneranative.core.model.ComicRef
import dev.veneranative.core.model.RemoteComicId
import dev.veneranative.core.model.SourceId
import dev.veneranative.core.model.comicKeyOrNull
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rules that make the shelf trustworthy: re-adding a comic does not reorder it, deleting a
 * folder never deletes the comics inside it, sorting is a query the repository picks rather than
 * something the caller does afterwards, and an update marker the user dismissed stays dismissed.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DefaultCollectionRepositoryTest {

    private val dao = FakeFavoriteDao()
    private val probe = FakeRemoteChapterProbe()
    private var now = 1_000L
    private val repository = DefaultCollectionRepository(
        dao = dao,
        updateMarker = UpdateMarker(probe),
        clock = { now },
    )

    private fun folder(
        id: String,
        name: String,
        sortOrder: Int,
        removable: Boolean = true,
    ) = FavoriteFolderEntity(folderId = id, name = name, sortOrder = sortOrder, removable = removable)

    private fun comic(refComic: String): ComicRef = ComicRef.Remote(
        ComicKey(SourceId("source-a"), RemoteComicId(refComic)),
    )

    private fun key(refComic: String): ComicKey = comic(refComic).comicKeyOrNull()!!

    private fun snapshot(
        title: String = "Comic",
        chapterCount: Int? = null,
        latestChapterId: String? = null,
    ) = ComicSnapshot(title = title, chapterCount = chapterCount, latestChapterId = latestChapterId)

    private fun row(
        refComic: String,
        title: String,
        addedAt: Long,
        lastReadAt: Long? = null,
        hasUpdate: Boolean = false,
        updatedAt: Long? = null,
        folderId: String = "",
    ) = FavoriteEntryEntity(
        refSource = "source-a",
        refComic = refComic,
        folderId = folderId,
        title = title,
        subtitle = null,
        coverRef = null,
        addedAt = addedAt,
        lastReadAt = lastReadAt,
        chapterCount = null,
        latestChapterId = null,
        hasUpdate = hasUpdate,
        updatedAt = updatedAt,
    )

    private suspend fun shelf(
        folderId: String? = null,
        sort: ShelfSort = ShelfSort.AddedAt,
    ): List<FavoriteItem> = repository.observeItems(folderId, sort).first()

    private suspend fun seedFolders() {
        dao.insertFolder(folder("reading", "Reading", 1))
        dao.insertFolder(folder("later", "Later", 2))
    }

    @Test fun `a new shelf is empty`() = runTest {
        assertTrue(shelf().isEmpty())
        assertTrue(repository.observeFolders().first().isEmpty())
    }

    @Test fun `a comic is added to the requested folder`() = runTest {
        seedFolders()

        repository.add(comic("comic-1"), "reading", snapshot("Comic One"))

        assertEquals(setOf("reading"), shelf("reading").single().folderIds)
        assertEquals("Comic One", shelf("reading").single().title)
    }

    @Test fun `adding the same comic twice keeps one row and the original timestamp`() = runTest {
        seedFolders()
        now = 1_000L
        repository.add(comic("comic-1"), "reading", snapshot("Comic One"))

        now = 9_000L
        repository.add(comic("comic-1"), "later", snapshot("Comic One v2", chapterCount = 3))

        val item = shelf().single()
        assertEquals(1_000L, item.addedAtEpochMillis)
        assertEquals("Comic One v2", item.title)
        assertEquals(setOf("later"), item.folderIds)
        assertEquals(3, item.chapterCount)
    }

    @Test fun `a comic filed into a folder that is gone remains in All`() = runTest {
        seedFolders()

        repository.add(comic("comic-1"), "deleted-earlier", snapshot("Comic One"))

        assertTrue(shelf().single().folderIds.isEmpty())
    }

    @Test fun `removing a comic drops it from the shelf`() = runTest {
        seedFolders()
        repository.add(comic("comic-1"), "reading", snapshot("Comic One"))

        repository.remove(comic("comic-1"))

        assertTrue(shelf().isEmpty())
    }

    @Test fun `a comic can be moved between folders`() = runTest {
        seedFolders()
        repository.add(comic("comic-1"), "reading", snapshot("Comic One"))

        repository.moveTo(comic("comic-1"), "later")

        assertTrue(shelf("reading").isEmpty())
        assertEquals(setOf("later"), shelf("later").single().folderIds)
    }

    @Test fun `moving a comic into a folder that is gone changes nothing`() = runTest {
        seedFolders()
        repository.add(comic("comic-1"), "reading", snapshot("Comic One"))

        repository.moveTo(comic("comic-1"), "deleted-earlier")

        assertEquals(setOf("reading"), shelf("reading").single().folderIds)
    }

    @Test fun `renaming a folder is visible to observers`() = runTest {
        seedFolders()

        repository.renameFolder("reading", "Favourites")

        assertEquals(
            "Favourites",
            repository.observeFolders().first().first { it.id == "reading" }.name,
        )
    }

    @Test fun `deleting a collection keeps its comics in All`() = runTest {
        seedFolders()
        repository.add(comic("comic-1"), "later", snapshot("Comic One"))

        repository.deleteFolder("later")

        assertTrue(shelf("later").isEmpty())
        assertEquals(1, shelf().size)
        assertTrue(shelf().single().folderIds.isEmpty())
    }

    @Test fun `All is implicit and cannot be deleted`() = runTest {
        seedFolders()
        repository.add(comic("comic-1"), DEFAULT_FOLDER_ID, snapshot("Comic One"))
        repository.deleteFolder(DEFAULT_FOLDER_ID)
        assertEquals(listOf("reading", "later"), repository.observeFolders().first().map { it.id })
        assertEquals(1, shelf().size)
    }

    @Test fun `a comic can belong to multiple collections and never disappears from All`() = runTest {
        seedFolders()
        val ref = comic("comic-1")
        repository.addToFolders(ref, setOf("reading", "later"), snapshot("Comic One"))
        assertEquals(setOf("reading", "later"), shelf().single().folderIds)
        assertEquals(1, shelf("reading").size)
        assertEquals(1, shelf("later").size)

        repository.setFolders(ref, setOf("later"))
        assertTrue(shelf("reading").isEmpty())
        assertEquals(1, shelf("later").size)
        assertEquals(1, shelf().size)

        repository.remove(ref)
        assertTrue(shelf().isEmpty())
        assertTrue(dao.memberships.value.isEmpty())
    }

    @Test fun `creating a folder appends it to the end`() = runTest {
        seedFolders()

        val id = repository.createFolder("  Queue  ")

        val folders = repository.observeFolders().first()
        assertEquals("Queue", folders.last().name)
        assertEquals(id, folders.last().id)
        assertTrue(folders.last().removable)
    }

    @Test fun `collection names are unique and All is reserved`() = runTest {
        seedFolders()
        assertTrue(runCatching { repository.createFolder("  READING  ") }.exceptionOrNull() is CollectionNameConflictException)
        assertTrue(runCatching { repository.createFolder("全部") }.exceptionOrNull() is CollectionNameConflictException)
        assertTrue(runCatching { repository.renameFolder("later", "Reading") }.exceptionOrNull() is CollectionNameConflictException)
        assertEquals(listOf("Reading", "Later"), repository.observeFolders().first().map { it.name })
    }

    @Test fun `each shelf order puts a different comic first`() = runTest {
        dao.entries.value = listOf(
            row("c1", "Zeta", addedAt = 4_000L, lastReadAt = 1_000L),
            row("c2", "Beta", addedAt = 3_000L, lastReadAt = 4_000L, hasUpdate = true, updatedAt = 5_000L),
            row("c3", "Alpha", addedAt = 2_000L, lastReadAt = 2_000L, hasUpdate = true, updatedAt = 9_000L),
            row("c4", "Omega", addedAt = 1_000L, lastReadAt = 3_000L, hasUpdate = true, updatedAt = 9_500L),
        )

        assertEquals("Zeta", shelf(sort = ShelfSort.AddedAt).first().title)
        assertEquals("Alpha", shelf(sort = ShelfSort.Title).first().title)
        assertEquals("Beta", shelf(sort = ShelfSort.LastRead).first().title)
        assertEquals("Omega", shelf(sort = ShelfSort.Updated).first().title)
    }

    @Test fun `a comic whose source gained chapters is marked and can be cleared`() = runTest {
        seedFolders()
        repository.add(
            comic("comic-1"), "reading",
            snapshot("Comic One", chapterCount = 10, latestChapterId = "ch-10"),
        )
        probe[key("comic-1")] = ChapterSnapshot(12, "ch-12")

        assertEquals(1, repository.refreshUpdates())
        assertTrue(shelf().single().hasUpdate)

        repository.clearUpdate(comic("comic-1"))
        assertFalse(shelf().single().hasUpdate)
    }

    @Test fun `a refresh after clearing does not raise the flag again`() = runTest {
        seedFolders()
        repository.add(
            comic("comic-1"), "reading",
            snapshot("Comic One", chapterCount = 10, latestChapterId = "ch-10"),
        )
        probe[key("comic-1")] = ChapterSnapshot(12, "ch-12")
        repository.refreshUpdates()
        repository.clearUpdate(comic("comic-1"))

        assertEquals(0, repository.refreshUpdates())
        assertFalse(shelf().single().hasUpdate)
    }

    @Test fun `a source that removes chapters is not an update`() = runTest {
        seedFolders()
        repository.add(
            comic("comic-1"), "reading",
            snapshot("Comic One", chapterCount = 10, latestChapterId = "ch-10"),
        )
        probe[key("comic-1")] = ChapterSnapshot(8, "ch-8")

        assertEquals(0, repository.refreshUpdates())
        // The snapshot still moves, so the next added chapter is compared against reality.
        assertEquals(8, shelf().single().chapterCount)
        assertEquals("ch-8", shelf().single().latestChapterId)
    }

    @Test fun `a comic whose source cannot answer keeps its stored marker`() = runTest {
        seedFolders()
        repository.add(
            comic("comic-1"), "reading",
            snapshot("Comic One", chapterCount = 10, latestChapterId = "ch-10"),
        )

        assertEquals(0, repository.refreshUpdates())
        assertEquals(10, shelf().single().chapterCount)
    }

    @Test fun `a refresh reports how many comics changed`() = runTest {
        seedFolders()
        repository.add(
            comic("comic-1"), "reading",
            snapshot("One", chapterCount = 10, latestChapterId = "ch-10"),
        )
        repository.add(
            comic("comic-2"), "reading",
            snapshot("Two", chapterCount = 10, latestChapterId = "ch-10"),
        )
        probe[key("comic-1")] = ChapterSnapshot(11, "ch-11")
        probe[key("comic-2")] = ChapterSnapshot(10, "ch-10")

        assertEquals(1, repository.refreshUpdates())
        assertEquals(listOf(true, false), shelf(sort = ShelfSort.Title).map { it.hasUpdate })
    }
}
