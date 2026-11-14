package dev.veneranative.app

/** Public repository coordinates the in-app self-update checks (ADR-0017). */
object SelfUpdateConfiguration {
    const val REPOSITORY_OWNER = "lwtor"
    const val REPOSITORY_NAME = "venera-native"

    /** Must match the `android:authorities` of the FileProvider declared in the manifest. */
    const val FILE_PROVIDER_AUTHORITY = "dev.veneranative.selfupdate"
}
