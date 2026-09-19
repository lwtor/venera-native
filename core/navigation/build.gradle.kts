plugins {
    id("venera.android.library")
}

android {
    namespace = "dev.veneranative.core.navigation"
}

dependencies {
    api(libs.androidx.navigation3.runtime)
}
