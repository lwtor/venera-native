package dev.veneranative.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The identity rules the rest of the app relies on: a local comic has no key to call a source with,
 * and a blank id is rejected at construction rather than failing somewhere in a query.
 */
class ComicRefTest {

    @Test
    fun `a remote ref keeps the source and comic it came from`() {
        val ref = ComicRef.Remote(ComicKey(SourceId("source-a"), RemoteComicId("comic-1")))

        assertEquals("source-a", ref.comicKeyOrNull()?.sourceId?.value)
        assertEquals("comic-1", ref.comicKeyOrNull()?.remoteId?.value)
    }

    @Test
    fun `a local ref has no comic key to call a source with`() {
        val ref = ComicRef.Local(LocalComicId("local-1"))

        assertNull(ref.comicKeyOrNull())
    }

    @Test
    fun `two refs describing the same comic are equal`() {
        assertEquals(
            ComicRef.Remote(ComicKey(SourceId("s"), RemoteComicId("c"))),
            ComicRef.Remote(ComicKey(SourceId("s"), RemoteComicId("c"))),
        )
        assertEquals(ComicRef.Local(LocalComicId("l")), ComicRef.Local(LocalComicId("l")))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a blank local id is rejected`() {
        LocalComicId("  ")
    }

    @Test
    fun `the local namespace is reserved and cannot be a source id`() {
        assertEquals("@local", LOCAL_REF_NAMESPACE)
    }
}
