plugins { id("venera.android.compose.library") }

android {
    namespace = "dev.veneranative.feature.library"
}

dependencies {
    implementation(project(":core:designsystem"))
    implementation(project(":core:image"))
    implementation(project(":core:model"))
    implementation(project(":data:collection"))
    implementation(project(":data:local"))
    implementation(project(":data:download"))

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    testImplementation(libs.junit4)
    testImplementation(libs.kotlinx.coroutines.test)

    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    debugImplementation(libs.compose.ui.test.manifest)
}
