plugins {
    id("venera.android.compose.library")
}

android {
    namespace = "dev.veneranative.core.image"
}

configurations.configureEach {
    if (name.endsWith("UnitTestRuntimeClasspath")) {
        exclude(group = "com.squareup.okhttp3", module = "okhttp-android")
    }
}

dependencies {
    // ComicPage, SourceId and PageImageSizer appear in public signatures.
    api(project(":core:model"))
    implementation(project(":core:network"))

    implementation(libs.coil.core)
    implementation(libs.coil.compose)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.foundation)
    implementation(libs.compose.ui)

    implementation(libs.okhttp)
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.okhttp.mockwebserver)
    testRuntimeOnly(libs.okhttp.jvm)
    testImplementation(libs.junit4)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.coil.test)
}
