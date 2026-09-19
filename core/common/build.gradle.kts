plugins {
    id("venera.android.library")
}

android {
    namespace = "dev.veneranative.core.common"
}

dependencies {
    implementation(libs.kotlinx.coroutines.core)
}
