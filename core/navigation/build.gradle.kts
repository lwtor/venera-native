plugins {
    id("venera.android.library")
}

android {
    namespace = "dev.veneranative.core.navigation"
}

dependencies {
    api(project(":core:model"))

    testImplementation(libs.junit4)
}
