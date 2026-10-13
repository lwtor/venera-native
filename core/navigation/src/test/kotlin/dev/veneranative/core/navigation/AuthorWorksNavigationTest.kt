package dev.veneranative.core.navigation

import dev.veneranative.core.model.ComicKey
import dev.veneranative.core.model.RemoteComicId
import dev.veneranative.core.model.SourceId
import dev.veneranative.core.model.TagNavigation
import org.junit.Assert.assertEquals
import org.junit.Test

class AuthorWorksNavigationTest {
    private val origin = ComicKey(SourceId("source:%"), RemoteComicId("comic:%"))
    @Test fun `author routes preserve target parameters options and return origin`() {
        listOf(TagNavigation.Search("作者:A:%", listOf("new", "")), TagNavigation.Category("A", "id:%", listOf("1"))).forEach { target ->
            val route = AppRoute.AuthorWorks(origin, "A:%", target, AppRoute.Search("source:%"))
            assertEquals(route, decodeAppRoute(route.encode()))
            assertEquals(AppRoute.ComicDetails(origin), backDestination(route, AppRoute.Home))
            assertEquals(route, detailsOriginAfterNavigation(route, AppRoute.ComicDetails(ComicKey(origin.sourceId, RemoteComicId("other"))), AppRoute.Home))
        }
    }
    @Test fun `nested author routes preserve the preceding result page`() {
        val parent = AppRoute.AuthorWorks(origin, "A", TagNavigation.Search("A"), AppRoute.Library)
        val child = AppRoute.AuthorWorks(origin, "B", TagNavigation.Category("B", "id"), parent)
        assertEquals(child, decodeAppRoute(child.encode()))
    }
}
