package dev.veneranative.core.navigation

import dev.veneranative.core.model.ChapterKey
import dev.veneranative.core.model.ChapterRef
import dev.veneranative.core.model.ComicKey
import dev.veneranative.core.model.RemoteChapterId
import dev.veneranative.core.model.RemoteComicId
import dev.veneranative.core.model.SourceId
import org.junit.Assert.assertEquals
import org.junit.Test

class DetailsOriginTest {
    private val comic = ComicKey(SourceId("source"), RemoteComicId("comic"))
    private val details = AppRoute.ComicDetails(comic)
    private val reader = AppRoute.Reader(ChapterRef.Remote(ChapterKey(comic, RemoteChapterId("chapter"))))

    @Test fun detailsBackReturnsToTheListThatOpenedIt() {
        for (origin in listOf(AppRoute.Library, AppRoute.Explore("source"), AppRoute.Search("source"))) {
            assertEquals(origin, detailsOriginAfterNavigation(origin, details, AppRoute.Home))
        }
    }

    @Test fun returningFromReaderKeepsTheOriginalList() {
        assertEquals(AppRoute.Search("source"),
            detailsOriginAfterNavigation(reader, details, AppRoute.Search("source")))
    }
}
