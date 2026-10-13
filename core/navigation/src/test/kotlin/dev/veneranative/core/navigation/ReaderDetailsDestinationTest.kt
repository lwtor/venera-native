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

class ReaderDetailsDestinationTest {
    private val comic = ComicKey(SourceId("fixture"), RemoteComicId("comic"))
    private val reader = AppRoute.Reader(ChapterRef.Remote(ChapterKey(comic, RemoteChapterId("chapter"))))
    private val details = AppRoute.ComicDetails(comic)

    @Test fun `details entry returns to same details and retains its list origin`() {
        val target = readerDetailsDestination(reader, details, AppRoute.Search("fixture"))!!
        assertEquals(details, target.route)
        assertEquals(AppRoute.Search("fixture"), backDestination(target.route, target.detailsOrigin))
    }

    @Test fun `direct reading replaces reader with details and returns to original entry`() {
        for (entry in listOf(AppRoute.Home, AppRoute.Library, AppRoute.Downloads)) {
            val target = readerDetailsDestination(reader, entry, AppRoute.Search("stale"))!!
            assertEquals(details, target.route)
            assertEquals(entry, backDestination(target.route, target.detailsOrigin))
        }
    }

    @Test fun `another comic details must not be reused and local reading has no remote details`() {
        val other = AppRoute.ComicDetails(ComicKey(SourceId("fixture"), RemoteComicId("other")))
        assertEquals(ReaderDetailsDestination(details, other), readerDetailsDestination(reader, other, AppRoute.Home))
        assertNull(readerDetailsDestination(
            AppRoute.Reader(ChapterRef.Local(LocalComicId("local"), LocalChapterId("chapter"))),
            AppRoute.Library, AppRoute.Home,
        ))
    }
}
