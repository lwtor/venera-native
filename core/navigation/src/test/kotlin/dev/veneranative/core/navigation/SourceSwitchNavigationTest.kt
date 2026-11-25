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

    @Test fun theSwitchPageSitsOnTopOfTheComicAndReturnsToIt() {
        val details = AppRoute.ComicDetails(comic, title = "作品", backTo = AppRoute.Search("source"))

        val switch = sourceSwitchDestination(details, "页面上显示的名字")

        assertEquals(comic, switch.comicKey)
        assertEquals("页面上显示的名字", switch.title)
        assertEquals(details, backDestination(switch))
        assertEquals(AppRoute.Search("source"), backDestination(details))
    }

    @Test fun theSwitchPageFallsBackToTheTitleItsComicWasOpenedWith() {
        val details = AppRoute.ComicDetails(comic, title = "作品")

        assertEquals("作品", sourceSwitchDestination(details, null).title)
        assertEquals("作品", sourceSwitchDestination(details, "  ").title)
    }

    @Test fun aComicPickedFromTheSwitchReturnsToThoseResultsAndKeepsComparingSources() {
        val details = AppRoute.ComicDetails(comic, backTo = AppRoute.Home)
        val switch = sourceSwitchDestination(details, "作品")
        val picked = AppRoute.ComicDetails(other, "作品", backTo = switch)

        assertEquals(switch, backDestination(picked))
        assertTrue(picked.openedBySourceSwitch())
    }

    @Test fun onlyPagesOpenedFromASwitchPageCountAsSwitchEntries() {
        assertFalse(AppRoute.ComicDetails(comic, backTo = AppRoute.Home).openedBySourceSwitch())
        assertTrue(AppRoute.ComicDetails(other, backTo = AppRoute.SourceSwitch(comic)).openedBySourceSwitch())
        assertFalse(AppRoute.ComicDetails(other).openedBySourceSwitch())
    }

    @Test fun aSwitchPageWithoutARecordedOriginFallsBackToHome() {
        assertEquals(AppRoute.Home, backDestination(AppRoute.SourceSwitch(comic)))
    }
}
