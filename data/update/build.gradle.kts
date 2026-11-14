plugins { id("venera.android.library") }

android { namespace = "dev.veneranative.data.update" }

dependencies {
    implementation(libs.okhttp)
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit4)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    // android.jar only carries org.json stubs.
    testImplementation(libs.json)
}
