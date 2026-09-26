package dev.veneranative.core.navigation

import dev.veneranative.core.model.ChapterKey
import dev.veneranative.core.model.ChapterRef
import dev.veneranative.core.model.ComicKey
import dev.veneranative.core.model.LocalChapterId
import dev.veneranative.core.model.LocalComicId
import dev.veneranative.core.model.RemoteChapterId
import dev.veneranative.core.model.RemoteComicId
import dev.veneranative.core.model.SourceId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BackDestinationTest {
    private val comic = ComicKey(SourceId("source"), RemoteComicId("comic"))
    private val origin = AppRoute.Search("source")

    @Test fun homeAllowsSystemExitAndTopLevelPagesReturnHome() {
        assertNull(backDestination(AppRoute.Home, origin))
        for (route in listOf(
            AppRoute.Sources,
            AppRoute.Library,
            AppRoute.Explore("source"),
            origin,
        )) {
            assertEquals(AppRoute.Home, backDestination(route, origin))
        }
    }

    @Test fun detailsAndReaderReturnAlongTheirOriginalPath() {
        val details = AppRoute.ComicDetails(comic)
        assertEquals(origin, backDestination(details, origin))
        assertEquals(details, backDestination(
            AppRoute.Reader(ChapterRef.Remote(ChapterKey(comic, RemoteChapterId("chapter")))),
            origin,
        ))
        assertEquals(AppRoute.Library, backDestination(
            AppRoute.Reader(ChapterRef.Local(LocalComicId("local"), LocalChapterId("chapter"))),
            origin,
        ))
    }
}
