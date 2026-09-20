plugins {
    id("venera.android.library")
}

android {
    namespace = "dev.veneranative.source.api"
}

dependencies {
    api(project(":core:model"))

    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit4)
    testImplementation(libs.kotlinx.coroutines.test)
}
