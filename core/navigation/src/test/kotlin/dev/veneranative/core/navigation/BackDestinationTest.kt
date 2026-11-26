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
    private val reader = AppRoute.Reader(ChapterRef.Remote(ChapterKey(comic, RemoteChapterId("chapter"))))

    @Test fun personalPagesReturnToProfileAndProfileReturnsHome() {
        assertEquals(AppRoute.Profile, backDestination(AppRoute.About))
        assertEquals(AppRoute.Profile, backDestination(AppRoute.Backup))
        assertEquals(AppRoute.Home, backDestination(AppRoute.Profile))
    }

    @Test fun homeAllowsSystemExitAndTopLevelPagesReturnTheirParent() {
        assertNull(backDestination(AppRoute.Home))
        for (route in listOf(
            AppRoute.Sources,
            AppRoute.SourceComicSearch,
            AppRoute.Library,
            AppRoute.Downloads,
            AppRoute.Explore("source"),
            AppRoute.Search("source"),
        )) {
            assertEquals(when (route) {
                AppRoute.Sources -> AppRoute.Profile
                AppRoute.SourceComicSearch -> AppRoute.Sources
                AppRoute.Downloads -> AppRoute.Library
                else -> AppRoute.Home
            }, backDestination(route))
        }
    }

    @Test fun aSourceSettingsPageReturnsToTheSourceList() {
        assertEquals(AppRoute.Sources, backDestination(AppRoute.SourceSettings(SourceId("demo"))))
        assertEquals(AppRoute.Sources, backDestination(AppRoute.SourceSettings(null)))
    }

    @Test fun detailsReturnToTheListTheyRecordedWhenTheyWereOpened() {
        for (origin in listOf(
            AppRoute.Library,
            AppRoute.Downloads,
            AppRoute.Explore("source"),
            AppRoute.Search("source"),
            AppRoute.Sources,
        )) {
            assertEquals(origin, backDestination(AppRoute.ComicDetails(comic, backTo = origin)))
        }
    }

    @Test fun detailsWithoutARecordedListReturnHome() {
        // Old links and restored state written before details carried their origin.
        assertEquals(AppRoute.Home, backDestination(AppRoute.ComicDetails(comic)))
    }

    @Test fun authorWorksReturnToDetailsThatReturnToWhereTheyBegan() {
        val works = AppRoute.AuthorWorks(comic, "author", dev.veneranative.core.model.TagNavigation.Search("author"),
            AppRoute.Search("source"))
        val details = backDestination(works) as AppRoute.ComicDetails
        assertEquals(comic, details.comicKey)
        assertEquals(AppRoute.Search("source"), backDestination(details))
    }

    @Test fun readerReturnsToTheRouteThatOpenedIt() {
        assertEquals(AppRoute.Home, backDestination(reader, AppRoute.Home))
        assertEquals(AppRoute.ComicDetails(comic), backDestination(reader, AppRoute.ComicDetails(comic)))
        assertEquals(AppRoute.Library, backDestination(reader, AppRoute.Library))
    }

    @Test fun localReadingWithoutAnOriginReturnsToTheShelf() {
        assertEquals(
            AppRoute.Library,
            backDestination(AppRoute.Reader(ChapterRef.Local(LocalComicId("local"), LocalChapterId("chapter")))),
        )
    }
}
