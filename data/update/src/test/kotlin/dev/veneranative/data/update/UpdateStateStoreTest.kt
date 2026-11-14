package dev.veneranative.data.update

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class UpdateStateStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `accepts a file starting with the zip local file header`() {
        val apk = File(tmp.newFolder(), "update.apk").apply {
            writeBytes(byteArrayOf(0x50, 0x4B, 0x03, 0x04, 0x00, 0x00, 0x00, 0x00))
        }
        assertTrue(isValidApk(apk))
    }

    @Test
    fun `rejects a file that does not start with the zip header`() {
        val notApk = File(tmp.newFolder(), "update.apk").apply { writeBytes("not an apk".toByteArray()) }
        assertFalse(isValidApk(notApk))
    }

    @Test
    fun `rejects a missing file`() {
        assertFalse(isValidApk(File(tmp.newFolder(), "missing.apk")))
    }

    @Test
    fun `rejects a file shorter than the header`() {
        val short = File(tmp.newFolder(), "update.apk").apply { writeBytes(byteArrayOf(0x50, 0x4B)) }
        assertFalse(isValidApk(short))
    }
}
