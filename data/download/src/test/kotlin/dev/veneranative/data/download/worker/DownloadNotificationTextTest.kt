package dev.veneranative.data.download.worker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The wording of the download notification, off-device.
 *
 * The notification is the only thing a user sees of a background run, and every string in it is a
 * promise about the queue: a count that cannot go backwards, a title that names the chapter being
 * worked on. Those are cheap to assert here and awkward to notice on a device.
 */
class DownloadNotificationTextTest {

    @Test
    fun `the channel id is one Android accepts`() {
        val id = DownloadNotificationText.CHANNEL_ID
        assertTrue("a channel id must not be blank", id.isNotBlank())
        assertTrue("a channel id must not contain whitespace: $id", id.none { it.isWhitespace() })
        assertTrue("a channel id must be shorter than 1000 characters", id.length < 1000)
    }

    @Test
    fun `the channel is described for the settings screen`() {
        assertTrue(DownloadNotificationText.channelName().isNotBlank())
        assertTrue(DownloadNotificationText.channelDescription().isNotBlank())
    }

    @Test
    fun `an unresolved run does not claim a chapter`() {
        assertEquals("Preparing download", DownloadNotificationText.title(DownloadProgress()))
        assertEquals("Starting", DownloadNotificationText.content(DownloadProgress()))
    }

    @Test
    fun `the title names the chapter being downloaded`() {
        val text = DownloadNotificationText.title(
            DownloadProgress(chapterTitle = "Chapter 7", completedPages = 3, totalPages = 20),
        )
        assertEquals("Downloading Chapter 7", text)
    }

    @Test
    fun `the content counts completed pages against the chapter`() {
        val text = DownloadNotificationText.content(
            DownloadProgress(chapterTitle = "Chapter 7", completedPages = 3, totalPages = 20),
        )
        assertEquals("3 / 20", text)
    }

    @Test
    fun `a chapter with no pages yet is not reported as zero of zero`() {
        val progress = DownloadProgress(chapterTitle = "Chapter 7")
        assertTrue(progress.isUnresolved)
        assertEquals("Starting", DownloadNotificationText.content(progress))
    }

    @Test
    fun `the three actions are distinct and stable`() {
        val actions = DownloadNotificationText.actions
        assertEquals(3, actions.size)
        assertTrue(DownloadNotificationText.ACTION_PAUSE in actions)
        assertTrue(DownloadNotificationText.ACTION_RESUME in actions)
        assertTrue(DownloadNotificationText.ACTION_CANCEL in actions)
        // The receiver matches on these exact strings, so they are part of the notification's
        // contract and must not drift with a wording change.
        assertTrue(DownloadNotificationText.ACTION_PAUSE.endsWith(".PAUSE"))
        assertTrue(DownloadNotificationText.ACTION_RESUME.endsWith(".RESUME"))
        assertTrue(DownloadNotificationText.ACTION_CANCEL.endsWith(".CANCEL"))
    }

    @Test
    fun `every action has a label a user can read`() {
        val labels = listOf(
            DownloadNotificationText.actionPause(),
            DownloadNotificationText.actionResume(),
            DownloadNotificationText.actionCancel(),
        )
        assertEquals(3, labels.distinct().size)
        for (label in labels) {
            assertTrue("an action label must not be blank", label.isNotBlank())
            assertFalse("an action label must not carry a count: $label", label.any { it.isDigit() })
        }
    }

    @Test
    fun `a progress that counts backwards is refused`() {
        assertThrows(IllegalArgumentException::class.java) {
            DownloadProgress(completedPages = -1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            DownloadProgress(totalPages = -1)
        }
    }
}
