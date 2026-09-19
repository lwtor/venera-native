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
    implementation("com.android.tools.build:gradle:9.4.0")
    implementation("org.jetbrains.kotlin.plugin.compose:org.jetbrains.kotlin.plugin.compose.gradle.plugin:2.2.10")
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
    }
}
