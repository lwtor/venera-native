package dev.veneranative.data.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleaseSelectionTest {

    @Test
    fun `picks the apk asset GitHub identifies as an android package`() {
        val apk = asset("venera-native-v1.6.0-1006000.apk", contentType = ReleaseSelection.APK_CONTENT_TYPE)
        val picked = ReleaseSelection.pickUpdateApk(
            listOf(
                asset("notes.txt", contentType = "text/plain"),
                apk,
                asset("source-code.zip", contentType = "application/zip"),
            ),
        )
        assertEquals(apk, picked)
    }

    @Test
    fun `picks an apk without a content type when no typed apk exists`() {
        val picked = ReleaseSelection.pickUpdateApk(
            listOf(
                asset("source-code.zip", contentType = "application/zip"),
                asset("venera-native-v1.6.0-1006000.apk", contentType = "application/octet-stream"),
                asset("venera-native-v1.6.0-1006001.apk", contentType = null),
            ),
        )
        assertEquals("venera-native-v1.6.0-1006000.apk", picked?.name)
    }

    @Test
    fun `returns null when the release has no apk at all`() {
        assertNull(ReleaseSelection.pickUpdateApk(listOf(asset("notes.txt", contentType = "text/plain"))))
        assertNull(ReleaseSelection.pickUpdateApk(emptyList()))
    }

    @Test
    fun `reads the version code from the release asset name`() {
        assertEquals(1_006_000L, ReleaseSelection.versionCodeFromAssetName("venera-native-v1.6.0-1006000.apk"))
        assertEquals(9L, ReleaseSelection.versionCodeFromAssetName("app-9.apk"))
        assertEquals(9L, ReleaseSelection.versionCodeFromAssetName("app-9.APK"))
    }

    @Test
    fun `rejects asset names without a trailing numeric code`() {
        assertNull(ReleaseSelection.versionCodeFromAssetName("venera-native.apk"))
        assertNull(ReleaseSelection.versionCodeFromAssetName("venera-native-v1.6.0.apk"))
        assertNull(ReleaseSelection.versionCodeFromAssetName("app-v1.6.0.apk"))
        assertNull(ReleaseSelection.versionCodeFromAssetName(""))
    }

    @Test
    fun `derives the version code from release tags`() {
        assertEquals(1_006_000L, ReleaseSelection.versionCodeFromTag("v1.6.0"))
        assertEquals(1_006_000L, ReleaseSelection.versionCodeFromTag("1.6.0"))
        assertEquals(1_000_000L, ReleaseSelection.versionCodeFromTag("v1.0.0"))
        assertEquals(2_000_010L, ReleaseSelection.versionCodeFromTag("v2.0.10"))
        assertEquals(1_006_000L, ReleaseSelection.versionCodeFromTag("v1.6"))
        assertEquals(999_999_999L, ReleaseSelection.versionCodeFromTag("v999.999.999"))
    }

    @Test
    fun `rejects unparsable or out-of-scheme tags`() {
        assertNull(ReleaseSelection.versionCodeFromTag(""))
        assertNull(ReleaseSelection.versionCodeFromTag("latest"))
        assertNull(ReleaseSelection.versionCodeFromTag("vX.Y.Z"))
        assertNull(ReleaseSelection.versionCodeFromTag("v1"))
        assertNull(ReleaseSelection.versionCodeFromTag("v1.6.0.4"))
        assertNull(ReleaseSelection.versionCodeFromTag("v1.1000.0"))
    }

    @Test
    fun `the asset name wins and the tag is only the fallback`() {
        assertEquals(1_006_000L, ReleaseSelection.releaseVersionCode("v1.5.0", "venera-native-v1.6.0-1006000.apk"))
        assertEquals(1_006_000L, ReleaseSelection.releaseVersionCode("v1.6.0", "venera-native.apk"))
        assertNull(ReleaseSelection.releaseVersionCode("latest", "venera-native.apk"))
        assertNull(ReleaseSelection.releaseVersionCode("latest", null))
    }

    @Test
    fun `an update needs a strictly larger version code`() {
        assertTrue(ReleaseSelection.isUpdate(1_006_000L, 1_005_000L))
        assertFalse(ReleaseSelection.isUpdate(1_005_000L, 1_005_000L))
        assertFalse(ReleaseSelection.isUpdate(1_004_000L, 1_005_000L))
        assertFalse(ReleaseSelection.isUpdate(null, 1_005_000L))
    }

    @Test
    fun `asset file names are stripped of path characters`() {
        assertEquals("a_b_c.apk", ReleaseSelection.sanitizeAssetFileName("a/b\\c.apk"))
        assertEquals("app-1.apk", ReleaseSelection.sanitizeAssetFileName("app-1.apk"))
    }

    private fun asset(name: String, contentType: String?) = GitHubReleaseAsset(
        name = name,
        downloadUrl = "https://example.com/$name",
        sizeBytes = 1024L,
        contentType = contentType,
    )
}
