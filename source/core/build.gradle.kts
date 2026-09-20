plugins {
    id("venera.android.library")
}

android {
    namespace = "dev.veneranative.source.core"
}

dependencies {
    api(project(":source:api"))

    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit4)
    testImplementation(libs.kotlinx.coroutines.test)
    // The adapter is exercised against a real engine on the JVM, so the tests use the desktop
    // engine artifact and drop the Android one (same reason as in :source:engine).
    testImplementation(project(":source:engine"))
    testImplementation(libs.quickjs.kt.jvm)
    // android.jar only carries org.json stubs.
    testImplementation(libs.json)
}

configurations.matching { it.name.contains("UnitTest") && it.name.endsWith("RuntimeClasspath") }
    .configureEach {
        exclude(group = "io.github.dokar3", module = "quickjs-kt-android")
    }
