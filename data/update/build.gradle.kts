plugins { id("venera.android.library") }

android { namespace = "dev.veneranative.data.update" }

dependencies {
    implementation(libs.okhttp)
    implementation(libs.kotlinx.coroutines.core)
    // FileProvider exposes the downloaded APK to the system installer.
    implementation(libs.androidx.core.ktx)

    testImplementation(libs.junit4)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    // android.jar only carries org.json stubs.
    testImplementation(libs.json)
}
