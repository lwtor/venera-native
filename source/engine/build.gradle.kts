plugins {
    id("venera.android.library")
}

android {
    namespace = "dev.veneranative.source.engine"

    defaultConfig {
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
}

dependencies {
    implementation(project(":source:api"))
    implementation(libs.androidx.javascriptengine)
    implementation(libs.quickjs.kt)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit4)
    testImplementation(libs.kotlinx.coroutines.test)
    // android.jar only carries org.json stubs, so the runtime's JSON boundaries are exercised
    // against a real implementation instead of the platform's "not mocked" placeholder.
    testImplementation(libs.json)
    // The engine is tested on the JVM: the binding ships a desktop artifact, so contract behaviour
    // (host callbacks, await, timeout, binary) does not need a device to be regression-checked.
    testImplementation(libs.quickjs.kt.jvm)

    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.okhttp)
    androidTestImplementation(libs.okhttp.mockwebserver)
    androidTestImplementation(project(":source:network"))
}

// Local unit tests must load the desktop native library, not the Android one. Leaving both on the
// test classpath would put the same classes there twice and fail on the first engine call.
configurations.matching { it.name.contains("UnitTest") && it.name.endsWith("RuntimeClasspath") }
    .configureEach {
        exclude(group = "io.github.dokar3", module = "quickjs-kt-android")
    }
