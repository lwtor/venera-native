package dev.veneranative.data.update

/**
 * Pure rules that turn a GitHub release into an update decision. No I/O so the decision logic
 * stays JVM-unit-testable; the versionCode scheme mirrors `build-logic` (`1.6.0` -> `1_006_000`).
 */
object ReleaseSelection {
    const val APK_CONTENT_TYPE = "application/vnd.android.package-archive"
    private val UNSAFE_FILE_CHARS = Regex("[^A-Za-z0-9._-]")

    /** Picks the APK to install; prefers assets GitHub identifies as Android packages. */
    fun pickUpdateApk(assets: List<GitHubReleaseAsset>): GitHubReleaseAsset? =
        assets.asSequence()
            .filter { it.name.endsWith(".apk", ignoreCase = true) }
            .sortedWith(
                compareByDescending<GitHubReleaseAsset> { it.contentType == APK_CONTENT_TYPE }
                    .thenByDescending { it.sizeBytes },
            )
            .firstOrNull()

    /** `venera-native-v1.6.0-1006000.apk` -> `1006000`; null when the name carries no code. */
    fun versionCodeFromAssetName(name: String): Long? {
        val stem = name.substringBeforeLast('.', missingDelimiterValue = "").trim()
        if (stem.isEmpty()) return null
        return stem.substringAfterLast('-').toLongOrNull()
    }

    /** `v1.6.0` -> `1,006,000` following the project versionCode scheme; null when unparsable. */
    fun versionCodeFromTag(tag: String): Long? {
        val segments = tag.trim()
            .removePrefix("v")
            .removePrefix("V")
            .split('.')
        if (segments.size !in 2..3) return null
        val major = segments[0].toIntOrNull() ?: return null
        val minor = segments.getOrNull(1)?.toIntOrNull() ?: return null
        val patch = segments.getOrNull(2)?.takeIf { it.isNotBlank() }?.toIntOrNull() ?: 0
        if (major < 0 || minor < 0 || patch < 0) return null
        if (major > 999 || minor > 999 || patch > 999) return null
        return major * 1_000_000L + minor * 1_000L + patch
    }

    /** The asset name wins because it is produced by our release tooling; the tag is the fallback. */
    fun releaseVersionCode(releaseTag: String, apkAssetName: String?): Long? =
        apkAssetName?.let(::versionCodeFromAssetName) ?: versionCodeFromTag(releaseTag)

    fun isUpdate(availableVersionCode: Long?, installedVersionCode: Long): Boolean =
        availableVersionCode != null && availableVersionCode > installedVersionCode

    /** Keeps a release asset name from ever escaping the cache directory as a path fragment. */
    fun sanitizeAssetFileName(name: String): String = name.replace(UNSAFE_FILE_CHARS, "_")
}
