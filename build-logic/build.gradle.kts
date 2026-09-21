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
    implementation("com.android.tools.build:gradle:9.2.1")
    implementation("org.jetbrains.kotlin.plugin.compose:org.jetbrains.kotlin.plugin.compose.gradle.plugin:2.2.10")
    // KSP has to be at least 2.3.4: earlier versions register generated sources through the
    // `kotlin.sourceSets` DSL, which AGP 9's built-in Kotlin rejects. 2.3.10 is the newest release
    // that still supports the KGP 2.2.10 that AGP 9.2.1 ships, so the Kotlin version stays put.
    // The same version is declared in the root build file's `buildscript` block.
    implementation("com.google.devtools.ksp:symbol-processing-gradle-plugin:2.3.10")
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
