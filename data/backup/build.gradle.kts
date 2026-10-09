plugins { id("venera.android.library") }

android { namespace = "dev.veneranative.data.backup" }

dependencies {
    implementation(project(":core:backup"))
    implementation(project(":core:database"))
    implementation(libs.androidx.room.ktx)
    testImplementation(libs.junit4)
}
