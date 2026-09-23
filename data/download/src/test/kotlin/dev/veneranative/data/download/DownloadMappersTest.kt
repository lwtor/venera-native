package dev.veneranative.data.download

import dev.veneranative.core.model.ChapterRef
import dev.veneranative.core.model.LOCAL_REF_NAMESPACE
import dev.veneranative.core.model.LocalChapterId
import dev.veneranative.core.model.LocalComicId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DownloadMappersTest {

    private val chapter = remoteChapter()
    private val local = ChapterRef.Local(LocalComicId("comic-7"), LocalChapterId("vol-1"))

    @Test
    fun `the same chapter always produces the same task id`() {
        assertEquals(chapter.taskId(), chapter.taskId())
        assertNotEquals(chapter.taskId(), remoteChapter(chapter = "ch-2").taskId())
    }

    @Test
    fun `a task id cannot be forged by shifting characters between the comic and the chapter`() {
        // "a/b" + "c" and "a" + "b/c" would collide in a naive join; the source length prefix stops it.
        assertNotEquals(
            remoteChapter(comic = "frieren", chapter = "extra/ch-1").taskId(),
            remoteChapter(comic = "frieren/extra", chapter = "ch-1").taskId(),
        )
    }

    @Test
    fun `a remote chapter survives the three columns it is stored in`() {
        assertEquals(
            chapter,
            chapterRefOf(refSourceOf(chapter), refComicOf(chapter), refChapterOf(chapter)),
        )
    }

    @Test
    fun `a local chapter is recorded under the reserved namespace`() {
        assertEquals(LOCAL_REF_NAMESPACE, refSourceOf(local))
        assertEquals(local, chapterRefOf(LOCAL_REF_NAMESPACE, "comic-7", "vol-1"))
    }

    @Test
    fun `a row that does not describe a chapter is not guessed at`() {
        assertNull(chapterRefOf("s", "c", " "))
        assertNull(chapterRefOf(" ", "c", "ch"))
    }

    @Test
    fun `a corrupt error carries its reason through the column and back`() {
        val error = DownloadError.Corrupt("the bytes are an HTML error page")

        assertEquals(error, errorFromColumn(error.toColumn()))
    }

    @Test
    fun `the errors without a reason survive the round trip`() {
        for (error in listOf(
            DownloadError.Network,
            DownloadError.StorageFull,
            DownloadError.NotResolvable,
        )) {
            assertEquals(error, errorFromColumn(error.toColumn()))
        }
    }

    @Test
    fun `a reason a newer version wrote is not turned into a guess`() {
        assertNull(errorFromColumn("SomethingFromTheFuture"))
        assertNull(errorFromColumn(null))
    }
}
