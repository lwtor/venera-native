package dev.veneranative.core.navigation

import dev.veneranative.core.model.ChapterKey
import dev.veneranative.core.model.ComicKey
import dev.veneranative.core.model.RemoteChapterId
import dev.veneranative.core.model.RemoteComicId
import dev.veneranative.core.model.SourceId
import dev.veneranative.core.model.ChapterRef
import dev.veneranative.core.model.LocalChapterId
import dev.veneranative.core.model.LocalComicId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Routes carry ids that come from source scripts, so the encoding has to survive the characters a
 * naive `split(':')` would break on.
 */
class AppRouteEncodingTest {

    private val routes = listOf(
        AppRoute.Home,
        AppRoute.Sources,
        AppRoute.Library,
        AppRoute.Explore(null),
        AppRoute.Search(null),
        AppRoute.Explore("manga_dex"),
        AppRoute.Search("manga_dex"),
        AppRoute.ComicDetails(comicKey("manga_dex", "a1b2c3")),
        AppRoute.ComicDetails(comicKey("manga_dex", "id:with:colons")),
        AppRoute.ComicDetails(comicKey("manga_dex", "100%25")),
        AppRoute.Reader(
            ChapterRef.Remote(ChapterKey(
                comicKey = comicKey("manga_dex", "a1b2c3"),
                remoteId = RemoteChapterId("chapter:1"),
            )),
        ),
        AppRoute.Reader(
            ChapterRef.Local(LocalComicId("local-comic"), LocalChapterId("local-chapter")),
        ),
    )

    @Test
    fun `every route survives a round trip`() {
        routes.forEach { route ->
            assertEquals(route, decodeAppRoute(route.encode()))
        }
    }

    @Test
    fun `an unknown route decodes to nothing instead of guessing`() {
        assertNull(decodeAppRoute("comic:only-one-part"))
        assertNull(decodeAppRoute("reader:source:comic"))
        assertNull(decodeAppRoute("something-else"))
        assertNull(decodeAppRoute(""))
    }

    @Test
    fun `the library route encodes to its own string`() {
        assertEquals("library", AppRoute.Library.encode())
        assertEquals(AppRoute.Library, decodeAppRoute("library"))
    }

    @Test
    fun `no source selected stays distinguishable from a source named empty`() {
        assertEquals(AppRoute.Explore(null), decodeAppRoute("explore:"))
    }

    private fun comicKey(source: String, comic: String) =
        ComicKey(SourceId(source), RemoteComicId(comic))
}
