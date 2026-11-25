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

    @Test fun aSwitchReturnsToThePageTheUserSwitchedFromRatherThanItsList() {
        // The page that switched was opened from search; the switch must not lose it.
        val from = AppRoute.ComicDetails(comic, backTo = AppRoute.Search("source"))

        val switched = sourceSwitchDestination(from, other, "title")

        assertEquals(other, switched.comicKey)
        assertEquals("title", switched.title)
        assertEquals(from, backDestination(switched))
        assertEquals(AppRoute.Search("source"), backDestination(from))
    }

    @Test fun repeatedSwitchesWalkBackThroughEverySourceInTurn() {
        val first = AppRoute.ComicDetails(comic, backTo = AppRoute.Home)
        val second = sourceSwitchDestination(first, other, null)
        val third = sourceSwitchDestination(second, comic, null)

        assertEquals(second, backDestination(third))
        assertEquals(first, backDestination(second))
        assertEquals(AppRoute.Home, backDestination(first))
    }

    @Test fun aPageIsMarkedAsSwitchEntryOnlyWhenAnotherDetailsOpenedIt() {
        assertTrue(AppRoute.ComicDetails(other, backTo = AppRoute.ComicDetails(comic)).openedBySourceSwitch())
        assertFalse(AppRoute.ComicDetails(other, backTo = AppRoute.Search("source")).openedBySourceSwitch())
        assertFalse(AppRoute.ComicDetails(other, backTo = AppRoute.Library).openedBySourceSwitch())
        assertFalse(AppRoute.ComicDetails(other).openedBySourceSwitch())
    }
}
