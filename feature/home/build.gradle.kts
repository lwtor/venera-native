plugins {
    id("venera.android.compose.library")
}

android {
    namespace = "dev.veneranative.feature.home"
}

dependencies {
    implementation("androidx.compose.material:material-icons-core")
    implementation(project(":core:designsystem"))
    implementation(project(":core:image"))
    implementation(project(":core:model"))
    implementation(project(":data:collection"))
    implementation(project(":data:history"))
    implementation(project(":data:local"))

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    testImplementation(libs.junit4)
    testImplementation(libs.kotlinx.coroutines.test)
}
