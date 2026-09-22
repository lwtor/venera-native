package dev.veneranative.data.collection

import dev.veneranative.core.model.ComicKey
import dev.veneranative.core.model.ComicRef
import dev.veneranative.core.model.LocalComicId
import dev.veneranative.core.model.RemoteComicId
import dev.veneranative.core.model.SourceId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What "has an update" means, expressed as tests rather than prose.
 *
 * The interesting cases are the ones a naive `count != storedCount` gets wrong: the very first
 * snapshot has nothing to compare against and must not light up every comic on the shelf, and a
 * source that *removes* chapters is not something the user asked to be told about.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class UpdateMarkerTest {

    private val probe = FakeRemoteChapterProbe()
    private val marker = UpdateMarker(probe)

    private val comicKey = ComicKey(SourceId("source-a"), RemoteComicId("comic-1"))

    private fun stored(
        chapterCount: Int? = 10,
        latestChapterId: String? = "ch-10",
    ) = FavoriteItem(
        ref = ComicRef.Remote(comicKey),
        title = "Comic One",
        folderId = "default",
        addedAtEpochMillis = 1_000L,
        chapterCount = chapterCount,
        latestChapterId = latestChapterId,
    )

    @Test fun `more chapters than the stored snapshot is an update`() = runTest {
        probe[comicKey] = ChapterSnapshot(12, "ch-12")

        assertEquals(UpdateState.Updated(ChapterSnapshot(12, "ch-12")), marker.evaluate(stored()))
    }

    @Test fun `the same chapter list is not an update`() = runTest {
        probe[comicKey] = ChapterSnapshot(10, "ch-10")

        assertEquals(UpdateState.Unchanged(ChapterSnapshot(10, "ch-10")), marker.evaluate(stored()))
    }

    @Test fun `a chapter replaced at the same count is an update`() = runTest {
        probe[comicKey] = ChapterSnapshot(10, "ch-11")

        assertEquals(UpdateState.Updated(ChapterSnapshot(10, "ch-11")), marker.evaluate(stored()))
    }

    @Test fun `fewer chapters than the stored snapshot is not an update`() = runTest {
        probe[comicKey] = ChapterSnapshot(8, "ch-8")

        val state = marker.evaluate(stored())
        assertTrue(state is UpdateState.Unchanged)
        // The snapshot is still returned so the caller can move it forward.
        assertEquals(ChapterSnapshot(8, "ch-8"), (state as UpdateState.Unchanged).snapshot)
    }

    @Test fun `the first snapshot is a baseline and never an update`() = runTest {
        probe[comicKey] = ChapterSnapshot(42, "ch-42")

        assertEquals(
            UpdateState.Unchanged(ChapterSnapshot(42, "ch-42")),
            marker.evaluate(stored(chapterCount = null, latestChapterId = null)),
        )
    }

    @Test fun `a source that cannot answer leaves the stored marker alone`() = runTest {
        probe[comicKey] = null

        assertEquals(UpdateState.Unknown, marker.evaluate(stored()))
    }

    @Test fun `an imported comic has no remote to ask`() = runTest {
        val local = stored().copy(ref = ComicRef.Local(LocalComicId("local-1")))

        assertEquals(UpdateState.Unknown, marker.evaluate(local))
        assertEquals(0, probe.calls)
    }
}
