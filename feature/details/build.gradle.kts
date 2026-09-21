plugins {
    id("venera.android.compose.library")
}

android {
    namespace = "dev.veneranative.feature.details"
}

dependencies {
    implementation(project(":core:designsystem"))
    implementation(project(":core:image"))
    implementation(project(":core:model"))
    implementation(project(":data:comic"))
    implementation(project(":source:api"))

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    // The details screen never pages, but its test doubles implement `ComicCatalog`, whose signatures
    // name Paging types.
    testImplementation(libs.androidx.paging.common)
    testImplementation(libs.junit4)
    testImplementation(libs.kotlinx.coroutines.test)

    debugImplementation(libs.compose.ui.test.manifest)
}
