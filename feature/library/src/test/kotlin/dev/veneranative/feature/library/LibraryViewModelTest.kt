package dev.veneranative.feature.library

import dev.veneranative.data.collection.ShelfSort
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The screen's four states and the guarantee behind them: the shelf is whatever the repository
 * streams, so changing folder or sort means one new query and never a re-sorted local copy.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LibraryViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val repository = FakeCollectionRepository()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `a shelf with comics loads into the ready state`() = runTest(dispatcher) {
        repository.folders.value = listOf(
            favoriteFolder("default", "Default", removable = false),
            favoriteFolder("reading", "Reading"),
        )
        repository.items.value = listOf(favoriteItem("comic-1", "Comic One"))

        val viewModel = LibraryViewModel(repository)
        advanceUntilIdle()

        assertEquals(LibraryStatus.Ready, viewModel.state.value.status)
        assertEquals(listOf("Comic One"), viewModel.state.value.items.map { it.title })
        assertEquals(listOf("default", "reading"), viewModel.state.value.folders.map { it.id })
    }

    @Test
    fun `an empty shelf is its own state`() = runTest(dispatcher) {
        val viewModel = LibraryViewModel(repository)
        advanceUntilIdle()

        assertEquals(LibraryStatus.Empty, viewModel.state.value.status)
        assertTrue(viewModel.state.value.items.isEmpty())
    }

    @Test
    fun `a shelf that cannot be read offers a retry that recovers`() = runTest(dispatcher) {
        repository.observeItemsError = IllegalStateException("database closed")
        val viewModel = LibraryViewModel(repository)
        advanceUntilIdle()

        assertEquals(LibraryStatus.Failed, viewModel.state.value.status)

        repository.observeItemsError = null
        repository.items.value = listOf(favoriteItem("comic-1"))
        viewModel.onAction(LibraryAction.Retry)
        advanceUntilIdle()

        assertEquals(LibraryStatus.Ready, viewModel.state.value.status)
    }

    @Test
    fun `switching folder and sort issues exactly one query each`() = runTest(dispatcher) {
        repository.folders.value = listOf(favoriteFolder("reading"))
        val viewModel = LibraryViewModel(repository)
        advanceUntilIdle()
        assertEquals(1, repository.observeItemQueries.size)

        viewModel.onAction(LibraryAction.SelectFolder("reading"))
        advanceUntilIdle()
        assertEquals(2, repository.observeItemQueries.size)

        // Selecting what is already selected must not re-run the query.
        viewModel.onAction(LibraryAction.SelectFolder("reading"))
        advanceUntilIdle()
        assertEquals(2, repository.observeItemQueries.size)

        viewModel.onAction(LibraryAction.ChangeSort(ShelfSort.Title))
        advanceUntilIdle()
        assertEquals(3, repository.observeItemQueries.size)
        assertEquals(ShelfSort.Title, repository.observeItemQueries.last().second)
        assertEquals("reading", repository.observeItemQueries.last().first)
    }

    @Test
    fun `a folder needs a name before it can be created`() = runTest(dispatcher) {
        val viewModel = LibraryViewModel(repository)
        advanceUntilIdle()

        viewModel.onAction(LibraryAction.EditFolder(null))
        viewModel.onAction(LibraryAction.FolderDraftChanged("   "))
        viewModel.onAction(LibraryAction.ConfirmFolderEditor)
        advanceUntilIdle()

        assertTrue(repository.createdFolders.isEmpty())
        assertEquals("Give the folder a name.", viewModel.state.value.message)
        assertNotNull(viewModel.state.value.folderEditor)
    }

    @Test
    fun `a named folder is created and the dialog closes`() = runTest(dispatcher) {
        val viewModel = LibraryViewModel(repository)
        advanceUntilIdle()

        viewModel.onAction(LibraryAction.EditFolder(null))
        viewModel.onAction(LibraryAction.FolderDraftChanged("  Queue  "))
        viewModel.onAction(LibraryAction.ConfirmFolderEditor)
        advanceUntilIdle()

        assertEquals(listOf("Queue"), repository.createdFolders)
        assertNull(viewModel.state.value.folderEditor)
    }

    @Test
    fun `renaming starts from the folder's current name`() = runTest(dispatcher) {
        repository.folders.value = listOf(favoriteFolder("reading", "Reading"))
        val viewModel = LibraryViewModel(repository)
        advanceUntilIdle()

        viewModel.onAction(LibraryAction.EditFolder("reading"))
        assertEquals("Reading", viewModel.state.value.folderEditor?.draft)

        viewModel.onAction(LibraryAction.FolderDraftChanged("Favourites"))
        viewModel.onAction(LibraryAction.ConfirmFolderEditor)
        advanceUntilIdle()

        assertEquals(listOf("reading" to "Favourites"), repository.renamedFolders)
    }

    @Test
    fun `deleting a folder forwards only its id`() = runTest(dispatcher) {
        repository.folders.value = listOf(favoriteFolder("reading", "Reading"))
        val viewModel = LibraryViewModel(repository)
        advanceUntilIdle()

        viewModel.onAction(LibraryAction.DeleteFolder("reading"))
        advanceUntilIdle()

        assertEquals(listOf("reading"), repository.deletedFolders)
        assertTrue(repository.observeItemQueries.isNotEmpty())
    }

    @Test
    fun `removing, moving and clearing an update reach the repository`() = runTest(dispatcher) {
        repository.items.value = listOf(favoriteItem("comic-1", hasUpdate = true))
        val viewModel = LibraryViewModel(repository)
        advanceUntilIdle()

        viewModel.onAction(LibraryAction.ClearUpdate(comicRef("comic-1")))
        viewModel.onAction(LibraryAction.MoveItem(comicRef("comic-1"), "reading"))
        viewModel.onAction(LibraryAction.RemoveItem(comicRef("comic-1")))
        advanceUntilIdle()

        assertEquals(listOf(comicRef("comic-1")), repository.clearedUpdates)
        assertEquals(listOf(comicRef("comic-1") to "reading"), repository.moved)
        assertEquals(listOf(comicRef("comic-1")), repository.removed)
    }

    @Test
    fun `an update check reports what it found and can be dismissed`() = runTest(dispatcher) {
        repository.refreshUpdatesResult = 3
        val viewModel = LibraryViewModel(repository)
        advanceUntilIdle()

        viewModel.onAction(LibraryAction.RefreshUpdates)
        advanceUntilIdle()

        assertEquals("3 comics have new chapters.", viewModel.state.value.message)

        viewModel.onAction(LibraryAction.DismissMessage)
        assertNull(viewModel.state.value.message)
    }

    @Test
    fun `an update check that fails says so instead of crashing`() = runTest(dispatcher) {
        repository.refreshUpdatesError = IllegalStateException("source unavailable")
        val viewModel = LibraryViewModel(repository)
        advanceUntilIdle()

        viewModel.onAction(LibraryAction.RefreshUpdates)
        advanceUntilIdle()

        assertEquals("The shelf could not be checked for updates.", viewModel.state.value.message)
    }
}
