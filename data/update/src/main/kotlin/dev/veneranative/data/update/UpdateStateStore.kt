package dev.veneranative.data.update

import android.content.Context
import java.io.File

/**
 * Persists which APK version has already been downloaded (and where it lives) so a subsequent
 * launch can skip the re-download and go straight to install. The store is deliberately tiny —
 * a single version code plus a path — and only ever trusts a record whose file still exists and
 * still starts with a ZIP local file header.
 */
class UpdateStateStore(context: Context) {
    private val preferences =
        context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    /**
     * Returns the previously downloaded APK for [versionCode], or null when the store holds no
     * such version, the recorded file is missing, or the file is no longer a valid APK. A dirty
     * record is cleared so it cannot poison a later lookup.
     */
    fun cachedApkForVersion(versionCode: Long): File? {
        val storedCode = preferences.getLong(KEY_VERSION_CODE, NO_VERSION)
        if (storedCode != versionCode) return null
        val path = preferences.getString(KEY_APK_PATH, null) ?: return null
        val file = File(path)
        if (!isValidApk(file)) {
            clear()
            return null
        }
        return file
    }

    fun saveDownloadedApk(versionCode: Long, apkPath: String) {
        preferences.edit()
            .putLong(KEY_VERSION_CODE, versionCode)
            .putString(KEY_APK_PATH, apkPath)
            .apply()
    }

    fun clear() {
        preferences.edit().remove(KEY_VERSION_CODE).remove(KEY_APK_PATH).apply()
    }

    private companion object {
        const val PREFERENCES_NAME = "self_update"
        const val KEY_VERSION_CODE = "downloaded_version_code"
        const val KEY_APK_PATH = "downloaded_apk_path"
        const val NO_VERSION = -1L
    }
}

/**
 * True when [file] exists and starts with a ZIP local file header (`PK\x03\x04`). APK files are
 * ZIP archives, so the magic number is a cheap sanity check that keeps a stale or corrupted file
 * from being handed to the installer. Pure JVM code so it is unit-testable without Android.
 */
internal fun isValidApk(file: File): Boolean {
    if (!file.isFile || file.length() < ZIP_LOCAL_FILE_HEADER.size) return false
    return runCatching {
        file.inputStream().use { input ->
            val header = ByteArray(ZIP_LOCAL_FILE_HEADER.size)
            input.read(header) == header.size && header.contentEquals(ZIP_LOCAL_FILE_HEADER)
        }
    }.getOrDefault(false)
}

private val ZIP_LOCAL_FILE_HEADER = byteArrayOf(0x50, 0x4B, 0x03, 0x04)
