plugins {
    id("venera.android.compose.library")
}

android {
    namespace = "dev.veneranative.core.designsystem"
}

dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.material3)
    implementation(libs.compose.ui)
}
