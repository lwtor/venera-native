plugins { id("venera.android.library") }

android { namespace = "dev.veneranative.data.update" }

dependencies {
    implementation(libs.okhttp)
    implementation(libs.kotlinx.coroutines.core)
    // FileProvider exposes the downloaded APK to the system installer.
    implementation(libs.androidx.core.ktx)
    // The download runs as a WorkManager worker so it survives the process and the device's own
    // constraints; the worker is the only Android-framework-touching file in this module.
    implementation(libs.androidx.work.runtime.ktx)

    testImplementation(libs.junit4)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    // android.jar only carries org.json stubs.
    testImplementation(libs.json)
}
