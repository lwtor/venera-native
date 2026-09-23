package dev.veneranative.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ChapterRefTest {

    private val comicKey = ComicKey(SourceId("manga_dex"), RemoteComicId("frieren"))

    @Test
    fun `a remote chapter keeps the comic and chapter it came from`() {
        val ref = ChapterRef.Remote(ChapterKey(comicKey, RemoteChapterId("ch-1")))

        assertEquals(comicKey, ref.key.comicKey)
        assertEquals("ch-1", ref.key.remoteId.value)
    }

    @Test
    fun `a local chapter keeps its comic and chapter ids`() {
        val ref = ChapterRef.Local(LocalComicId("comic-7"), LocalChapterId("vol-1"))

        assertEquals("comic-7", ref.comicId.value)
        assertEquals("vol-1", ref.chapterId.value)
    }

    @Test
    fun `both kinds name the comic they belong to`() {
        assertEquals(
            ComicRef.Remote(comicKey),
            ChapterRef.Remote(ChapterKey(comicKey, RemoteChapterId("ch-1"))).comicRef,
        )
        assertEquals(
            ComicRef.Local(LocalComicId("comic-7")),
            ChapterRef.Local(LocalComicId("comic-7"), LocalChapterId("vol-1")).comicRef,
        )
    }

    @Test
    fun `the same chapter under two comics is not the same chapter`() {
        val first = ChapterRef.Remote(
            ChapterKey(ComicKey(SourceId("s"), RemoteComicId("comic-a")), RemoteChapterId("ch-1")),
        )
        val second = ChapterRef.Remote(
            ChapterKey(ComicKey(SourceId("s"), RemoteComicId("comic-b")), RemoteChapterId("ch-1")),
        )

        assertNotEquals(first, second)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a blank local chapter id is rejected`() {
        LocalChapterId(" ")
    }
}
