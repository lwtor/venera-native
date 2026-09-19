plugins {
    id("venera.android.library")
}

android {
    namespace = "dev.veneranative.core.network"
}

dependencies {
    api(libs.okhttp)
    testImplementation(libs.junit4)
}
