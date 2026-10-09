plugins { id("venera.android.compose.library") }

android { namespace = "dev.veneranative.feature.backup" }

dependencies {
    implementation(project(":core:backup"))
    implementation(project(":core:designsystem"))
    implementation(project(":data:backup"))
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.kotlinx.coroutines.core)
}
