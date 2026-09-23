plugins { id("venera.android.library") }

android {
    namespace = "dev.veneranative.data.download"
}

dependencies {
    // Chapter and page identity appear in the repository's signatures, so callers need the model.
    api(project(":core:model"))
    implementation(project(":core:database"))
    // Pages are fetched through the image pipeline (same auth and cache as reading) and validated
    // with its header parser, which is pure Kotlin and therefore testable off-device.
    implementation(project(":core:image"))

    implementation(libs.kotlinx.coroutines.core)
    // `chapter.json` uses the tree API only, so no serialization compiler plugin is needed.
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit4)
    testImplementation(libs.kotlinx.coroutines.test)
}
