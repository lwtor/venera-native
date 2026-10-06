plugins {
    id("venera.android.compose.library")
}

android {
    namespace = "dev.veneranative.feature.search"
}

dependencies {
    implementation(project(":core:designsystem"))
    implementation(project(":core:model"))
    implementation(project(":data:comic"))
    api(project(":data:search"))
    implementation(project(":data:settings"))
    implementation(project(":core:image"))
    implementation(project(":source:api"))

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.foundation)
    implementation("androidx.compose.material:material-icons-core")
    implementation(libs.compose.material3)
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.paging.compose)

    testImplementation(libs.junit4)
    testImplementation(libs.kotlinx.coroutines.test)

    debugImplementation(libs.compose.ui.test.manifest)
}
