plugins {
    id("venera.android.library")
}

android {
    namespace = "dev.veneranative.data.comic"
}

dependencies {
    api(project(":core:model"))
    implementation(project(":data:source"))
    implementation(project(":source:api"))

    implementation(libs.androidx.paging.common)
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit4)
    testImplementation(libs.kotlinx.coroutines.test)
}
