plugins { id("venera.android.library") }

android { namespace = "dev.veneranative.data.local" }

dependencies {
    api(project(":core:model"))
    implementation(project(":core:database"))
    implementation(libs.androidx.documentfile)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.androidx.room.ktx)

    testImplementation(libs.junit4)
    testImplementation(libs.kotlinx.coroutines.test)
}
