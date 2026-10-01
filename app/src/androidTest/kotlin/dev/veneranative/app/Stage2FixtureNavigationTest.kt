package dev.veneranative.app

import android.util.Log
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.printToString
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import dev.veneranative.core.model.ComicKey
import dev.veneranative.core.model.ComicRef
import dev.veneranative.core.model.RemoteComicId
import dev.veneranative.core.model.SourceId
import dev.veneranative.data.source.InstallOutcome
import java.io.File
import java.util.UUID
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** D01: exercises the real app graph, fixture source and all three details origins. */
class Stage2FixtureNavigationTest {
    @get:Rule val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun exploreSearchAndShelfReturnToTheirOwnOrigins() {
        Log.i(TAG, "Activity rule completed; obtaining app graph")
        val activity = composeRule.activity
        val graph = ViewModelProvider(activity)[AppGraph::class.java]
        val sourceId = SourceId("stage2_d01_${UUID.randomUUID().toString().replace("-", "")}")
        val comicRef = ComicRef.Remote(ComicKey(sourceId, RemoteComicId("c1")))
        Log.i(TAG, "Waiting for collection repository")
        val collection = runBlocking {
            withTimeout(20_000) { graph.collection.filterNotNull().first() }
        }
        val script = File(activity.cacheDir, "stage2_d01_source.js")
        InstrumentationRegistry.getInstrumentation().context.assets.open("demo_comic_source.js").use { input ->
            val fixture = input.bufferedReader().readText()
            check("this.key = \"demo_comic_source\";" in fixture)
            check("this.name = \"Demo Comic Source\";" in fixture)
            script.writeText(
                fixture.replace("this.key = \"demo_comic_source\";", "this.key = \"${sourceId.value}\";")
                    .replace("this.name = \"Demo Comic Source\";", "this.name = \"D01 Fixture Source\";"),
            )
        }

        try {
            Log.i(TAG, "Installing fixture source")
            assertTrue(runBlocking {
                withTimeout(20_000) { graph.sourceRepository.install(script.absolutePath) }
            } is InstallOutcome.Success)

            Log.i(TAG, "Checking Explore path")
            click("Explore")
            waitFor("Demo Comic c1")
            // With several installed sources, the fixture appears as a source chip.
            selectFixtureSourceIfOffered()
            click("Demo Comic c1")
            waitFor("D01 Fixture Source")
            click("Add to shelf")
            click("Read")
            waitForPageOne()
            click("Back")
            waitFor("Chapter 1")
            systemBack()
            waitFor("Demo Comic c1")
            systemBack()
            waitFor("Android-native foundation is ready")

            Log.i(TAG, "Checking Search path")
            click("Search")
            waitFor("Keyword")
            selectFixtureSourceIfOffered()
            composeRule.onNodeWithText("Keyword").performTextInput("Demo")
            composeRule.onNode(hasText("Search") and hasClickAction()).performClick()
            click("Demo Comic search-1")
            waitFor("D01 Fixture Source")
            waitFor("Chapter 1")
            click("Read")
            waitForPageOne()
            systemBack()
            waitFor("Chapter 1")
            click("Back")
            waitFor("Demo Comic search-1")
            systemBack()
            waitFor("Android-native foundation is ready")

            Log.i(TAG, "Checking Library path")
            click("Library")
            click("Favorites")
            click("Demo Comic c1")
            waitFor("Chapter 1")
            click("Read")
            waitForPageOne()
            click("Back")
            waitFor("Chapter 1")
            systemBack()
            waitFor("Favorites")
            waitFor("Demo Comic c1")
            systemBack()
            waitFor("Android-native foundation is ready")
        } finally {
            Log.i(TAG, "Cleaning fixture data")
            try {
                runBlocking {
                    withTimeout(20_000) {
                        graph.progressTracker.get()?.flush()
                        val history = graph.history.filterNotNull().first()
                        history.remove(comicRef.key)
                        history.remove(ComicKey(sourceId, RemoteComicId("search-1")))
                        collection.remove(comicRef)
                        graph.sourceRepository.uninstall(sourceId)
                    }
                }
            } finally {
                script.delete()
            }
        }
    }

    private fun click(text: String) {
        waitFor(text)
        composeRule.onAllNodesWithText(text)[0].performClick()
    }

    private fun selectFixtureSourceIfOffered() {
        val chips = composeRule.onAllNodes(hasText("D01 Fixture Source") and hasClickAction())
        if (chips.fetchSemanticsNodes().isNotEmpty()) chips[0].performClick()
    }

    private fun waitFor(vararg texts: String) {
        composeRule.waitUntil(timeoutMillis = 20_000) {
            texts.any { text -> composeRule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
        }
    }

    private fun waitForPageOne() {
        try {
            waitFor("1 / 3")
        } catch (error: Throwable) {
            throw AssertionError("Reader did not show a three-page chapter: ${composeRule.onRoot().printToString()}", error)
        }
        composeRule.waitUntil(timeoutMillis = 20_000) {
            composeRule.onAllNodesWithContentDescription("Page 1").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onAllNodesWithContentDescription("Page 1")[0].assertIsDisplayed()
    }

    private fun systemBack() {
        composeRule.runOnUiThread { composeRule.activity.onBackPressedDispatcher.onBackPressed() }
    }

    private companion object {
        const val TAG = "Stage2FixtureNavigation"
    }
}
