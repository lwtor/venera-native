plugins { id("venera.android.library") }

android { namespace = "dev.veneranative.data.history" }

dependencies {
    api(project(":core:model"))
    implementation(project(":core:database"))
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit4)
    testImplementation(libs.kotlinx.coroutines.test)
}
