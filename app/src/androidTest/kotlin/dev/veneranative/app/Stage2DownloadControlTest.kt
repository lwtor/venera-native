package dev.veneranative.app

import android.app.NotificationManager
import android.provider.Settings
import android.util.Log
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onParent
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import dev.veneranative.core.model.ChapterKey
import dev.veneranative.core.model.ChapterRef
import dev.veneranative.core.model.ComicKey
import dev.veneranative.core.model.RemoteChapterId
import dev.veneranative.core.model.RemoteComicId
import dev.veneranative.core.model.SourceId
import dev.veneranative.data.download.DownloadChapterState
import dev.veneranative.data.download.DownloadEnvironment
import dev.veneranative.data.download.DownloadPageState
import dev.veneranative.data.download.DownloadRepository
import dev.veneranative.data.download.worker.DownloadNotificationText
import dev.veneranative.data.source.InstallOutcome
import java.io.File
import java.util.UUID
import kotlin.math.abs
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** D02: real details/library controls against the loopback slow-page fixture. */
class Stage2DownloadControlTest {
    @get:Rule val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun enqueueSlowChapterForProcessRestart() {
        val target = prepareD03Target()
        click("Explore")
        waitFor("D02 Slow Comic")
        selectFixtureSourceIfOffered()
        click("D02 Slow Comic")
        waitFor("D02 Slow Chapter")
        click("Download")
        waitFor("Chapter queued for download.")
        val graph = ViewModelProvider(composeRule.activity)[AppGraph::class.java]
        val repository = runBlocking { withTimeout(20_000) { graph.download.filterNotNull().first() } }
        waitForTask(repository, target.chapter, 20_000) { it.state == DownloadChapterState.Running }
        Log.i(TAG, "D03_PROCESS_RESTART_READY chapter=${target.chapter}")
    }

    @Test
    fun runningChapterCompletesAfterAppProcessRestart() {
        val activity = composeRule.activity
        val graph = ViewModelProvider(activity)[AppGraph::class.java]
        val repository = runBlocking { withTimeout(20_000) { graph.download.filterNotNull().first() } }
        val sourceId = SourceId(D03_SOURCE_ID)
        val chapter = ChapterRef.Remote(
            ChapterKey(ComicKey(sourceId, RemoteComicId("slow")), RemoteChapterId("ch1")),
        )

        try {
            val completed = runBlocking {
                withTimeout(120_000) {
                    repository.observeTask(chapter).first { it?.state == DownloadChapterState.Completed }!!
                }
            }
            assertEquals(3, completed.completedPages)
            assertTrue(runBlocking { repository.isCompleteOffline(chapter) })
            assertTrue(runBlocking { repository.pagesOf(chapter) }.all { it.state == DownloadPageState.Succeeded })
        } finally {
            runBlocking {
                withTimeout(20_000) {
                    repository.cancel(chapter)
                    graph.sourceRepository.uninstall(sourceId)
                }
            }
            File(activity.cacheDir, "$D03_SOURCE_ID.js").delete()
        }
    }

    @Test
    fun offlineChapterDisplaysImagesAndRestoresProgressInAirplaneMode() {
        val activity = composeRule.activity
        val graph = ViewModelProvider(activity)[AppGraph::class.java]
        val repository = runBlocking { withTimeout(20_000) { graph.download.filterNotNull().first() } }
        val sourceId = SourceId("stage2_d04_${UUID.randomUUID().toString().replace("-", "")}")
        val comicKey = ComicKey(sourceId, RemoteComicId("slow"))
        val chapter = ChapterRef.Remote(ChapterKey(comicKey, RemoteChapterId("ch1")))
        val script = File(activity.cacheDir, "${sourceId.value}.js")

        try {
            InstrumentationRegistry.getInstrumentation().context.assets.open("download_control_source.js").use { input ->
                val fixture = input.bufferedReader().readText()
                script.writeText(fixture.replace(
                    "this.key = \"download_control_source\";",
                    "this.key = \"${sourceId.value}\";",
                ))
            }
            assertTrue(runBlocking {
                withTimeout(20_000) { graph.sourceRepository.install(script.absolutePath) }
            } is InstallOutcome.Success)

            click("Explore")
            waitFor("D02 Slow Comic")
            selectFixtureSourceIfOffered()
            click("D02 Slow Comic")
            waitFor("D02 Slow Chapter")
            click("Add to shelf")
            click("Download")
            waitFor("Chapter queued for download.")
            waitForTask(repository, chapter, 20_000) { it.pageCount == 3 }

            click("Back")
            click("Back")
            click("Library")
            click("Downloads")
            waitForTask(repository, chapter, 120_000) { it.state == DownloadChapterState.Completed }
            assertTrue(runBlocking { repository.isCompleteOffline(chapter) })

            click("Favorites")
            click("D02 Slow Comic")
            waitFor("D02 Slow Chapter")
            Log.i(TAG, "D04_AIRPLANE_READY chapter=$chapter")
            composeRule.waitUntil(timeoutMillis = 120_000) {
                Settings.Global.getInt(activity.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) == 1
            }

            click("Read")
            waitForReaderPage(1)
            composeRule.onAllNodesWithContentDescription("Page 1")[0].performTouchInput { swipeUp() }
            waitFor("2 / 3")
            waitForReaderPage(2)
            click("Back")
            runBlocking { graph.progressTracker.get()?.flush() }

            click("Read")
            waitFor("2 / 3")
            waitForReaderPage(2)
            composeRule.onAllNodesWithContentDescription("Page 2")[0].performTouchInput { swipeUp() }
            waitFor("3 / 3")
            waitForReaderPage(3)
            Log.i(TAG, "D04_OFFLINE_REOPEN_PASS chapter=$chapter")
        } finally {
            runBlocking {
                withTimeout(20_000) {
                    repository.cancel(chapter)
                    graph.collection.filterNotNull().first().remove(dev.veneranative.core.model.ComicRef.Remote(comicKey))
                    graph.history.filterNotNull().first().remove(comicKey)
                    graph.sourceRepository.uninstall(sourceId)
                }
            }
            script.delete()
        }
    }

    @Test
    fun slowChapterCanPauseResumeAndRemove() {
        val activity = composeRule.activity
        val graph = ViewModelProvider(activity)[AppGraph::class.java]
        val repository = runBlocking { withTimeout(20_000) { graph.download.filterNotNull().first() } }
        val sourceId = SourceId("stage2_d02_${UUID.randomUUID().toString().replace("-", "")}")
        val chapter = ChapterRef.Remote(
            ChapterKey(ComicKey(sourceId, RemoteComicId("slow")), RemoteChapterId("ch1")),
        )
        val script = File(activity.cacheDir, "stage2_d02_source.js")
        InstrumentationRegistry.getInstrumentation().context.assets.open("download_control_source.js").use { input ->
            val fixture = input.bufferedReader().readText()
            check("this.key = \"download_control_source\";" in fixture)
            script.writeText(fixture.replace(
                "this.key = \"download_control_source\";",
                "this.key = \"${sourceId.value}\";",
            ))
        }

        try {
            Log.i(TAG, "Installing D02 fixture source")
            assertTrue(runBlocking {
                withTimeout(20_000) { graph.sourceRepository.install(script.absolutePath) }
            } is InstallOutcome.Success)

            click("Explore")
            waitFor("D02 Slow Comic")
            selectFixtureSourceIfOffered()
            click("D02 Slow Comic")
            waitFor("D02 Slow Chapter")
            click("Download")
            waitFor("Chapter queued for download.")
            waitForTask(repository, chapter, 20_000) { it.pageCount == 3 }

            click("Back")
            click("Back")
            click("Library")
            click("Downloads")
            waitFor("D02 Slow Chapter")
            val notificationManager = activity.getSystemService(NotificationManager::class.java)
            if (notificationManager.areNotificationsEnabled()) {
                composeRule.waitUntil(timeoutMillis = 20_000) {
                    notificationManager.activeNotifications.any { it.id == DownloadNotificationText.NOTIFICATION_ID }
                }
            } else {
                Log.w(TAG, "Notification permission disabled; drawer visibility needs separate verification")
            }

            Log.i(TAG, "Pausing slow chapter")
            taskAction("D02 Slow Chapter", "Pause")
            val paused = waitForTask(repository, chapter, 60_000) { it.state == DownloadChapterState.Paused }
            assertTrue(paused.completedPages < 3)
            assertEquals(3, runBlocking { repository.pagesOf(chapter) }.size)

            Log.i(TAG, "Resuming slow chapter")
            taskAction("D02 Slow Chapter", "Resume")
            val completed = waitForTask(repository, chapter, 120_000) {
                it.state == DownloadChapterState.Completed
            }
            assertEquals(3, completed.completedPages)
            assertTrue(runBlocking { repository.isCompleteOffline(chapter) })

            Log.i(TAG, "Removing completed chapter")
            taskAction("D02 Slow Chapter", "Remove")
            composeRule.waitUntil(timeoutMillis = 20_000) {
                runBlocking { repository.observeTask(chapter).first() } == null
            }
            val chapterDir = DownloadEnvironment.get(activity).layout().chapterDir(sourceId.value, "slow", "ch1")
            assertFalse(chapterDir.exists())
        } finally {
            runBlocking {
                withTimeout(20_000) {
                    repository.cancel(chapter)
                    graph.sourceRepository.uninstall(sourceId)
                }
            }
            script.delete()
        }
    }

    @Test
    fun failedChapterCanRetryFromDownloads() {
        val activity = composeRule.activity
        val graph = ViewModelProvider(activity)[AppGraph::class.java]
        val repository = runBlocking { withTimeout(20_000) { graph.download.filterNotNull().first() } }
        val sourceId = SourceId("stage2_d02_retry_${UUID.randomUUID().toString().replace("-", "")}")
        val chapter = ChapterRef.Remote(
            ChapterKey(ComicKey(sourceId, RemoteComicId("retry")), RemoteChapterId("ch1")),
        )
        val script = File(activity.cacheDir, "stage2_d02_retry_source.js")
        InstrumentationRegistry.getInstrumentation().context.assets.open("download_control_source.js").use { input ->
            val fixture = input.bufferedReader().readText()
            check("this.key = \"download_control_source\";" in fixture)
            script.writeText(fixture.replace(
                "this.key = \"download_control_source\";",
                "this.key = \"${sourceId.value}\";",
            ))
        }

        try {
            Log.i(TAG, "Installing D02 retry fixture source")
            assertTrue(runBlocking {
                withTimeout(20_000) { graph.sourceRepository.install(script.absolutePath) }
            } is InstallOutcome.Success)

            click("Explore")
            waitFor("D02 Retry Comic")
            selectFixtureSourceIfOffered()
            click("D02 Retry Comic")
            waitFor("D02 Retry Chapter")
            click("Download")
            waitFor("Chapter queued for download.")
            waitForTask(repository, chapter, 20_000) { it.pageCount == 3 }

            click("Back")
            click("Back")
            click("Library")
            click("Downloads")
            val failed = waitForTask(repository, chapter, 60_000) {
                it.state == DownloadChapterState.Partial
            }
            assertEquals(2, failed.completedPages)
            val failedPages = runBlocking { repository.pagesOf(chapter) }
            assertEquals(1, failedPages.count { it.state == DownloadPageState.Failed })
            assertEquals(1, failedPages.single { it.state == DownloadPageState.Failed }.attempts)
            waitFor("D02 Retry Chapter")

            Log.i(TAG, "Retrying failed D02 chapter")
            taskAction("D02 Retry Chapter", "Retry")
            val completed = waitForTask(repository, chapter, 60_000) {
                it.state == DownloadChapterState.Completed
            }
            assertEquals(3, completed.completedPages)
            assertTrue(runBlocking { repository.isCompleteOffline(chapter) })
            assertTrue(runBlocking { repository.pagesOf(chapter) }.all { it.state == DownloadPageState.Succeeded })
        } finally {
            runBlocking {
                withTimeout(20_000) {
                    repository.cancel(chapter)
                    graph.sourceRepository.uninstall(sourceId)
                }
            }
            script.delete()
        }
    }

    private fun click(text: String) {
        waitFor(text)
        composeRule.onAllNodesWithText(text)[0].performClick()
    }

    private fun taskAction(title: String, action: String) {
        val titleY = composeRule.onNodeWithText(title).fetchSemanticsNode().boundsInRoot.center.y
        val actionNodes = composeRule.onAllNodes(hasText(action) and hasClickAction())
        val candidates = actionNodes.fetchSemanticsNodes()
        val targetIndex = candidates.indices.minByOrNull { index ->
            abs(candidates[index].boundsInRoot.center.y - titleY)
        } ?: error("No clickable '$action' control is visible for '$title'")
        actionNodes[targetIndex].performClick()
    }

    private fun selectFixtureSourceIfOffered() {
        val chips = composeRule.onAllNodes(hasText("D02 Download Fixture") and hasClickAction())
        if (chips.fetchSemanticsNodes().isNotEmpty()) chips[0].performClick()
    }

    private fun prepareD03Target(): D03Target {
        val activity = composeRule.activity
        val graph = ViewModelProvider(activity)[AppGraph::class.java]
        val sourceId = SourceId(D03_SOURCE_ID)
        val chapter = ChapterRef.Remote(
            ChapterKey(ComicKey(sourceId, RemoteComicId("slow")), RemoteChapterId("ch1")),
        )
        val script = File(activity.cacheDir, "$D03_SOURCE_ID.js")
        runBlocking {
            withTimeout(20_000) {
                graph.download.filterNotNull().first().cancel(chapter)
                graph.sourceRepository.uninstall(sourceId)
            }
        }
        InstrumentationRegistry.getInstrumentation().context.assets.open("download_control_source.js").use { input ->
            val fixture = input.bufferedReader().readText()
            script.writeText(fixture.replace(
                "this.key = \"download_control_source\";",
                "this.key = \"$D03_SOURCE_ID\";",
            ))
        }
        assertTrue(runBlocking {
            withTimeout(20_000) { graph.sourceRepository.install(script.absolutePath) }
        } is InstallOutcome.Success)
        return D03Target(chapter)
    }

    private fun waitFor(text: String) {
        composeRule.waitUntil(timeoutMillis = 20_000) {
            composeRule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun waitForReaderPage(number: Int) {
        val label = "Page $number"
        composeRule.waitUntil(timeoutMillis = 20_000) {
            composeRule.onAllNodesWithContentDescription(label).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onAllNodesWithContentDescription(label)[0].assertIsDisplayed()
    }

    private fun waitForTask(
        repository: DownloadRepository,
        chapter: ChapterRef,
        timeoutMillis: Long,
        predicate: (dev.veneranative.data.download.DownloadTask) -> Boolean,
    ): dev.veneranative.data.download.DownloadTask {
        composeRule.waitUntil(timeoutMillis = timeoutMillis) {
            runBlocking { repository.observeTask(chapter).first() }?.let(predicate) == true
        }
        return requireNotNull(runBlocking { repository.observeTask(chapter).first() })
    }

    private companion object {
        const val TAG = "Stage2DownloadControl"
        const val D03_SOURCE_ID = "stage2_d03_process"
    }

    private data class D03Target(val chapter: ChapterRef)
}
