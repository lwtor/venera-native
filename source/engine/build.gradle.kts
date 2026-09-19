plugins {
    id("venera.android.library")
}

android {
    namespace = "dev.veneranative.source.engine"
}

dependencies {
    implementation(project(":source:api"))
    implementation(libs.androidx.javascriptengine)
    implementation(libs.kotlinx.coroutines.core)
}
