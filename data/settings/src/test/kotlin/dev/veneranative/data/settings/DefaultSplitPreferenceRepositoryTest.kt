package dev.veneranative.data.settings

import dev.veneranative.core.model.ChapterKey
import dev.veneranative.core.model.ChapterRef
import dev.veneranative.core.model.ComicKey
import dev.veneranative.core.model.ComicRef
import dev.veneranative.core.model.LocalChapterId
import dev.veneranative.core.model.LocalComicId
import dev.veneranative.core.model.PageSplit
import dev.veneranative.core.model.RemoteChapterId
import dev.veneranative.core.model.RemoteComicId
import dev.veneranative.core.model.SourceId
import dev.veneranative.core.model.stableId
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DefaultSplitPreferenceRepositoryTest {

    private class InMemoryPreferences : ScreenPreferenceRepository {
        val map = mutableMapOf<String, String>()
        override suspend fun get(key: String): String? = map[key]
        override suspend fun put(key: String, value: String) {
            map[key] = value
        }

        override suspend fun remove(key: String) {
            map.remove(key)
        }
    }

    private val remote = ChapterRef.Remote(
        ChapterKey(
            comicKey = ComicKey(SourceId("source-a"), RemoteComicId("comic-1")),
            remoteId = RemoteChapterId("chapter-1"),
        ),
        group = "vol-1",
    )

    @Test
    fun `split enabled persists and round trips`() = runTest {
        val store = InMemoryPreferences()
        val repo = DefaultSplitPreferenceRepository(store)

        assertFalse(repo.isSplitEnabled(remote))
        repo.setSplitEnabled(remote, true)
        assertTrue(repo.isSplitEnabled(remote))
        assertEquals(1, store.map.size)

        repo.setSplitEnabled(remote, false)
        assertFalse(repo.isSplitEnabled(remote))
        assertTrue(store.map.isEmpty())
    }

    @Test
    fun `page exclusion persists and round trips per source page`() = runTest {
        val store = InMemoryPreferences()
        val repo = DefaultSplitPreferenceRepository(store)

        assertFalse(repo.isPageExcluded(remote, 0))
        assertFalse(repo.isPageExcluded(remote, 1))

        repo.setPageExcluded(remote, 0, true)
        assertTrue(repo.isPageExcluded(remote, 0))
        assertFalse(repo.isPageExcluded(remote, 1))

        repo.setPageExcluded(remote, 0, false)
        assertFalse(repo.isPageExcluded(remote, 0))
    }

    @Test
    fun `remote and local chapters never share a stable id`() = runTest {
        val local = ChapterRef.Local(LocalComicId("comic-1"), LocalChapterId("chapter-1"))
        assertNotEquals(remote.stableId(), local.stableId())
        assertTrue(remote.stableId().startsWith("remote:"))
        assertTrue(local.stableId().startsWith("local:"))
    }

    @Test
    fun `group and source participate in the stable id`() {
        val otherGroup = remote.copy(group = "vol-2")
        val otherSource = ChapterRef.Remote(
            ChapterKey(
                comicKey = ComicKey(SourceId("source-b"), RemoteComicId("comic-1")),
                remoteId = RemoteChapterId("chapter-1"),
            ),
            group = "vol-1",
        )
        val noGroup = remote.copy(group = null)

        assertNotEquals(remote.stableId(), otherGroup.stableId())
        assertNotEquals(remote.stableId(), otherSource.stableId())
        assertNotEquals(remote.stableId(), noGroup.stableId())
    }

    @Test
    fun `split and exclusion keys do not collide`() = runTest {
        val store = InMemoryPreferences()
        val repo = DefaultSplitPreferenceRepository(store)

        repo.setSplitEnabled(remote, true)
        repo.setPageExcluded(remote, 0, true)

        assertTrue(repo.isSplitEnabled(remote))
        assertTrue(repo.isPageExcluded(remote, 0))
        assertEquals(2, store.map.size)
    }

    @Test
    fun `per-comic split settings default when never written`() = runTest {
        val store = InMemoryPreferences()
        val repo = DefaultSplitPreferenceRepository(store)

        assertEquals(PageSplit.DEFAULT_OVERLAP_FRACTION, repo.overlapFraction(remote.comicRef), 0f)
        assertFalse(repo.isRightToLeft(remote.comicRef))
        assertTrue(store.map.isEmpty())
    }

    @Test
    fun `overlap fraction round trips`() = runTest {
        val store = InMemoryPreferences()
        val repo = DefaultSplitPreferenceRepository(store)

        repo.setOverlapFraction(remote.comicRef, 0.15f)
        assertEquals(0.15f, repo.overlapFraction(remote.comicRef), 1e-6f)

        repo.setOverlapFraction(remote.comicRef, 0f)
        assertEquals(0f, repo.overlapFraction(remote.comicRef), 0f)
    }

    @Test
    fun `unreadable overlap value falls back to the default`() = runTest {
        val store = InMemoryPreferences()
        store.put("split:comic:${remote.comicRef.stableId()}:overlap", "not-a-number")
        val repo = DefaultSplitPreferenceRepository(store)

        assertEquals(PageSplit.DEFAULT_OVERLAP_FRACTION, repo.overlapFraction(remote.comicRef), 0f)
    }

    @Test
    fun `split order round trips and stays scoped to one comic`() = runTest {
        val store = InMemoryPreferences()
        val repo = DefaultSplitPreferenceRepository(store)
        val otherComic = ComicRef.Remote(ComicKey(SourceId("source-a"), RemoteComicId("comic-2")))

        repo.setRightToLeft(remote.comicRef, true)
        assertTrue(repo.isRightToLeft(remote.comicRef))
        assertFalse(repo.isRightToLeft(otherComic))

        repo.setRightToLeft(remote.comicRef, false)
        assertFalse(repo.isRightToLeft(remote.comicRef))
    }

    @Test
    fun `overlap stays scoped to one comic and is shared by all its chapters`() = runTest {
        val store = InMemoryPreferences()
        val repo = DefaultSplitPreferenceRepository(store)
        val siblingChapter = remote.copy(key = remote.key.copy(remoteId = RemoteChapterId("chapter-2")))
        val otherComic = ComicRef.Remote(ComicKey(SourceId("source-a"), RemoteComicId("comic-2")))

        repo.setOverlapFraction(remote.comicRef, 0.1f)

        assertEquals(0.1f, repo.overlapFraction(siblingChapter.comicRef), 1e-6f)
        assertEquals(PageSplit.DEFAULT_OVERLAP_FRACTION, repo.overlapFraction(otherComic), 0f)
    }
}
