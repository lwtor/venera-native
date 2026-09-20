plugins {
    id("venera.android.library")
}

android {
    namespace = "dev.veneranative.source.api"
}

dependencies {
    api(project(":core:model"))

    testImplementation(libs.junit4)
    testImplementation(libs.kotlinx.coroutines.test)
}
