package dev.veneranative.core.navigation

import dev.veneranative.core.model.ComicKey
import dev.veneranative.core.model.RemoteComicId
import dev.veneranative.core.model.SourceId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceSwitchNavigationTest {
    private val comic = ComicKey(SourceId("source"), RemoteComicId("comic"))
    private val other = ComicKey(SourceId("other"), RemoteComicId("comic"))

    @Test fun aSwitchReturnsToTheResultsItStartedFrom() {
        val from = AppRoute.ComicDetails(comic, title = "作品", backTo = AppRoute.Search("source"))

        val switched = sourceSwitchDestination(from, other, "作品")

        assertEquals(other, switched.comicKey)
        assertEquals("作品", switched.title)
        // Back lands on the page that ran the search, and that page re-opens its results.
        assertEquals(from.copy(showSourceSwitch = true), backDestination(switched))
        assertEquals(AppRoute.Search("source"), backDestination(from))
    }

    @Test fun everyStepBackOntoASwitchPageReopensItsResults() {
        val first = AppRoute.ComicDetails(comic, backTo = AppRoute.Home)
        val second = sourceSwitchDestination(first, other, null)
        val third = sourceSwitchDestination(second, comic, null)

        assertEquals(second.copy(showSourceSwitch = true), backDestination(third))
        assertEquals(first.copy(showSourceSwitch = true), backDestination(second))
        assertEquals(AppRoute.Home, backDestination(first))
    }

    @Test fun onlyThePagesASwitchOpenedCountAsSwitchEntries() {
        val from = AppRoute.ComicDetails(comic, backTo = AppRoute.Home)
        val switched = sourceSwitchDestination(from, other, null)

        assertTrue(switched.openedBySourceSwitch())
        assertFalse(from.openedBySourceSwitch())
        assertFalse(AppRoute.ComicDetails(other, backTo = AppRoute.Search("source")).openedBySourceSwitch())
        assertFalse(AppRoute.ComicDetails(other, backTo = AppRoute.Library).openedBySourceSwitch())
        assertFalse(AppRoute.ComicDetails(other).openedBySourceSwitch())
    }
}
