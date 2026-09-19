plugins {
    id("venera.android.compose.library")
}

android {
    namespace = "dev.veneranative.feature.home"
}

dependencies {
    implementation(project(":core:designsystem"))

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
}
