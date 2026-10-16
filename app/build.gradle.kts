import java.util.Properties

plugins {
    id("venera.android.application")
}

val releasePropertiesFile = rootProject.file("keystore.properties")
val releaseProperties = Properties().apply {
    if (releasePropertiesFile.isFile) releasePropertiesFile.inputStream().use { load(it) }
}
val releaseSigningReady = listOf("storeFile", "storePassword", "keyAlias", "keyPassword")
    .all { !releaseProperties.getProperty(it).isNullOrBlank() }

android {
    namespace = "dev.veneranative.app"

    signingConfigs {
        create("projectDebug") {
            storeFile = rootProject.file("signing/venera-native-debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        if (releaseSigningReady) {
            create("release") {
                storeFile = rootProject.file(releaseProperties.getProperty("storeFile"))
                storePassword = releaseProperties.getProperty("storePassword")
                keyAlias = releaseProperties.getProperty("keyAlias")
                keyPassword = releaseProperties.getProperty("keyPassword")
            }
        }
    }

    sourceSets.getByName("androidTest").assets.directories.add(rootProject.file("tools/test-sources").path)

    defaultConfig {
        applicationId = "dev.veneranative"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("projectDebug")
        }
        release {
            if (releaseSigningReady) signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
}

dependencies {
    implementation(project(":core:designsystem"))
    implementation(project(":core:image"))
    implementation(project(":core:database"))
    implementation(project(":core:model"))
    // Named directly because the assembly layer builds the shared OkHttp client that both the
    // source calls and the comic image pipeline use.
    implementation(project(":core:network"))
    implementation(project(":core:navigation"))
    implementation(project(":data:comic"))
    implementation(project(":data:collection"))
    implementation(project(":data:backup"))
    // The assembly layer installs the download environment the worker reads (ADR-0005 §2.5).
    implementation(project(":data:download"))
    // The app declares WorkManager's foreground service in its merged manifest, so keep the
    // runtime dependency visible to app lint and manifest validation rather than relying on a
    // transitive implementation detail of :data:download.
    implementation(libs.androidx.work.runtime.ktx)
    implementation(project(":data:history"))
    implementation(project(":data:local"))
    implementation(project(":data:search"))
    implementation(project(":data:settings"))
    implementation(project(":data:source"))
    implementation(project(":feature:details"))
    implementation(project(":feature:backup"))
    implementation(project(":feature:profile"))
    implementation(libs.compose.material.icons.core)
    implementation(project(":feature:explore"))
    implementation(project(":feature:home"))
    implementation(project(":feature:library"))
    implementation(project(":feature:reader"))
    implementation(project(":feature:search"))
    implementation(project(":feature:sources"))
    // The assembly layer names engine contracts directly, so it declares them instead of relying on
    // transitive exposure from the implementation modules.
    implementation(project(":source:api"))
    implementation(project(":source:core"))
    implementation(project(":source:engine"))
    // The Host API implementation lives with the network stack (ADR-0001); the assembly layer is
    // what pairs it with an engine.
    implementation(project(":source:network"))

    implementation(platform(libs.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    // The assembly layer builds the ImageLoader that `ComicImage` consumes through composition local,
    // so it names Coil's Compose artifact even though it draws no images itself.
    implementation(libs.coil.compose)
    debugImplementation(libs.compose.ui.tooling)

    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.paging.common)
    debugImplementation(libs.compose.ui.test.manifest)
}
