plugins {
    id("venera.android.application")
}

android {
    namespace = "dev.veneranative.app"

    defaultConfig {
        applicationId = "dev.veneranative"
    }

    buildTypes {
        release {
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
    // The assembly layer installs the download environment the worker reads (ADR-0005 §2.5).
    implementation(project(":data:download"))
    // The app declares WorkManager's foreground service in its merged manifest, so keep the
    // runtime dependency visible to app lint and manifest validation rather than relying on a
    // transitive implementation detail of :data:download.
    implementation(libs.androidx.work.runtime.ktx)
    implementation(project(":data:history"))
    implementation(project(":data:local"))
    implementation(project(":data:source"))
    implementation(project(":feature:details"))
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
    implementation(libs.compose.material3)
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    // The assembly layer builds the ImageLoader that `ComicImage` consumes through composition local,
    // so it names Coil's Compose artifact even though it draws no images itself.
    implementation(libs.coil.compose)
    debugImplementation(libs.compose.ui.tooling)
}
