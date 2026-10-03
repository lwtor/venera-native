plugins { id("venera.android.library") }

android { namespace = "dev.veneranative.data.search" }

dependencies {
    implementation(project(":core:database"))
    implementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.junit4)
    testImplementation(libs.kotlinx.coroutines.test)
}
