plugins { id("venera.android.library") }

android { namespace = "dev.veneranative.core.backup" }

dependencies {
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.junit4)
}
