plugins { id("venera.android.library") }

android { namespace = "dev.veneranative.data.backup" }

dependencies {
    implementation(project(":core:backup"))
    implementation(project(":core:database"))
    implementation(libs.androidx.room.ktx)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.junit4)
}
