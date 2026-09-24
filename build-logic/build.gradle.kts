plugins {
    `kotlin-dsl`
}

group = "dev.veneranative.buildlogic"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(17)
    }
}

dependencies {
    implementation("com.android.tools.build:gradle:9.3.1")
    implementation("org.jetbrains.kotlin.plugin.compose:org.jetbrains.kotlin.plugin.compose.gradle.plugin:2.4.20")
    // KSP 2.3.12 supports Kotlin 2.4 metadata and AGP 9 built-in Kotlin.
    implementation("com.google.devtools.ksp:symbol-processing-gradle-plugin:2.3.12")
}

gradlePlugin {
    plugins {
        register("androidApplication") {
            id = "venera.android.application"
            implementationClass = "AndroidApplicationConventionPlugin"
        }
        register("androidLibrary") {
            id = "venera.android.library"
            implementationClass = "AndroidLibraryConventionPlugin"
        }
        register("androidComposeLibrary") {
            id = "venera.android.compose.library"
            implementationClass = "AndroidComposeLibraryConventionPlugin"
        }
        register("androidRoomLibrary") {
            id = "venera.android.room.library"
            implementationClass = "AndroidRoomLibraryConventionPlugin"
        }
    }
}
