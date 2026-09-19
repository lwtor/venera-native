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
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit4)

    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.kotlinx.coroutines.test)
}
