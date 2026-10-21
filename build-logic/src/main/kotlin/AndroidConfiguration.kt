import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.dsl.LibraryExtension
import org.gradle.api.JavaVersion

internal fun ApplicationExtension.configureVeneraAndroidApplication() {
    compileSdk = 37

    defaultConfig {
        minSdk = 26
        targetSdk = 37
        // Keep this mapping monotonic and leave 1,000 values for patch/minor changes.
        // 1.0.0 -> 1,000,000; 1.2.1 -> 1,002,001. Keep within Android's signed Int range.
        versionCode = 1_002_001
        versionName = "1.2.1"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    lint {
        abortOnError = true
        checkReleaseBuilds = true
        warningsAsErrors = true
        // Pinned to the newest AGP supported by the project Android Studio baseline.
        disable += "AndroidGradlePluginVersion"
    }
}

internal fun LibraryExtension.configureVeneraAndroidLibrary(compose: Boolean) {
    compileSdk = 37

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        this.compose = compose
    }

    lint {
        abortOnError = true
        warningsAsErrors = true
        // Pinned to the newest AGP supported by the project Android Studio baseline.
        disable += "AndroidGradlePluginVersion"
    }
}
