plugins { id("venera.android.room.library") }

android {
    namespace = "dev.veneranative.core.database"

    // `MigrationTestHelper` reads the exported schema out of the test APK's assets, so the schemas
    // directory has to ship with the androidTest build or every migration case would fail looking
    // for `1.json`.
    sourceSets {
        getByName("androidTest") {
            assets.srcDir("$projectDir/schemas")
        }
    }
}

dependencies {
    api(libs.androidx.room.runtime)
    api(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    androidTestImplementation(libs.androidx.room.testing)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.kotlinx.coroutines.test)
}
