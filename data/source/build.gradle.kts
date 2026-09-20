plugins {
    id("venera.android.library")
}

android {
    namespace = "dev.veneranative.data.source"
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":source:api"))
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit4)
    testImplementation(libs.kotlinx.coroutines.test)
}
