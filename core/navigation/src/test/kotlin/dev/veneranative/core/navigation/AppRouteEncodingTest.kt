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
        AppRoute.SourceComicSearch,
        AppRoute.Library,
        AppRoute.ReadingHistory,
        AppRoute.Downloads,
        AppRoute.Profile,
        AppRoute.About,
        AppRoute.Backup,
        AppRoute.Explore(null),
        AppRoute.Search(null),
        AppRoute.Explore("manga_dex"),
        AppRoute.Search("manga_dex"),
        AppRoute.ComicDetails(comicKey("manga_dex", "a1b2c3")),
        AppRoute.ComicDetails(comicKey("manga_dex", "id:with:colons")),
        AppRoute.ComicDetails(comicKey("manga_dex", "100%25")),
        AppRoute.ComicDetails(comicKey("manga_dex", "a1b2c3"), title = "葬送的芙莉莲: 第1卷 100%"),
        AppRoute.ComicDetails(comicKey("manga_dex", "a1b2c3"), showSourceSwitch = true),
        AppRoute.ComicDetails(
            comicKey("manga_dex", "a1b2c3"),
            title = "作品",
            backTo = AppRoute.ComicDetails(comicKey("copy_manga", "42"), showSourceSwitch = true),
            showSourceSwitch = true,
        ),
        AppRoute.Reader(
            ChapterRef.Remote(ChapterKey(
                comicKey = comicKey("manga_dex", "a1b2c3"),
                remoteId = RemoteChapterId("chapter:1"),
            ), group = "版本:繁中"),
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
        assertEquals("downloads", AppRoute.Downloads.encode())
        assertEquals(AppRoute.Downloads, decodeAppRoute("downloads"))
        assertEquals("reading-history", AppRoute.ReadingHistory.encode())
        assertEquals(AppRoute.ReadingHistory, decodeAppRoute("reading-history"))
    }

    @Test
    fun `no source selected stays distinguishable from a source named empty`() {
        assertEquals(AppRoute.Explore(null), decodeAppRoute("explore:"))
    }

    @Test
    fun `a details route without a title still decodes, so older links keep working`() {
        assertEquals(
            AppRoute.ComicDetails(comicKey("manga_dex", "a1b2c3")),
            decodeAppRoute("comic:manga_dex:a1b2c3"),
        )
    }

    @Test
    fun `links written before the switch flag decode it as off`() {
        assertEquals(
            AppRoute.ComicDetails(comicKey("manga_dex", "a1b2c3"), title = "作品", backTo = AppRoute.Home),
            decodeAppRoute("comic:manga_dex:a1b2c3:作品:home"),
        )
        assertEquals(
            AppRoute.ComicDetails(comicKey("manga_dex", "a1b2c3"), showSourceSwitch = true),
            decodeAppRoute("comic:manga_dex:a1b2c3:::1"),
        )
    }

    private fun comicKey(source: String, comic: String) =
        ComicKey(SourceId(source), RemoteComicId(comic))
}
