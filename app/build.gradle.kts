import java.util.Properties
import java.security.KeyStore
import java.security.MessageDigest
import java.io.File

plugins {
    id("venera.android.application")
}

val vaultPropertiesFile = rootProject.file("keystore-vault.properties")
val releasePropertiesFile = if (vaultPropertiesFile.isFile) vaultPropertiesFile else rootProject.file("keystore.properties")
val releaseProperties = Properties().apply {
    if (releasePropertiesFile.isFile) releasePropertiesFile.inputStream().use { load(it) }
}
val releaseSigningReady = listOf("storeFile", "storePassword", "keyAlias", "keyPassword")
    .all { !releaseProperties.getProperty(it).isNullOrBlank() }
val releaseStoreFile = releaseProperties.getProperty("storeFile")?.let { rootProject.file(it) }
val releaseStorePassword = releaseProperties.getProperty("storePassword")
val releaseKeyAlias = releaseProperties.getProperty("keyAlias")
val releaseKeyPassword = releaseProperties.getProperty("keyPassword")
val releaseFingerprintFile = rootProject.file("signing/release-cert.sha256")

val verifyReleaseSigningIdentity = tasks.register("verifyReleaseSigningIdentity") {
    group = "verification"
    description = "Reject a missing or incorrect release signing identity."
    notCompatibleWithConfigurationCache("Signing verification uses local credentials and must not cache them.")
    doLast {
        check(releaseSigningReady && releaseStoreFile?.isFile == true) {
            "Release signing is not configured. Restore the signing vault; see docs/release.md."
        }
        val expected = releaseFingerprintFile.readText().trim().replace(":", "").lowercase()
        check(expected.matches(Regex("[0-9a-f]{64}"))) { "Invalid release certificate fingerprint." }
        val password = requireNotNull(releaseStorePassword).toCharArray()
        val keyPassword = requireNotNull(releaseKeyPassword).toCharArray()
        try {
            val store = KeyStore.getInstance(requireNotNull(releaseStoreFile), password)
            val alias = requireNotNull(releaseKeyAlias)
            check(store.isKeyEntry(alias) && store.getKey(alias, keyPassword) != null) {
                "Release signing alias does not contain an accessible private key."
            }
            val certificate = requireNotNull(store.getCertificate(alias)) { "Release certificate is missing." }
            val actual = MessageDigest.getInstance("SHA-256").digest(certificate.encoded)
                .joinToString("") { "%02x".format(it) }
            check(actual == expected) { "Release signing certificate does not match the project fingerprint." }
        } finally {
            password.fill('\u0000')
            keyPassword.fill('\u0000')
        }
    }
}

tasks.configureEach {
    if (name == "preReleaseBuild") dependsOn(verifyReleaseSigningIdentity)
}

val localSdkProperties = Properties().apply {
    val localProperties = rootProject.file("local.properties")
    if (localProperties.isFile) localProperties.inputStream().use { load(it) }
}
val sdkLocation = localSdkProperties.getProperty("sdk.dir")
    ?: System.getenv("ANDROID_HOME")
    ?: System.getenv("ANDROID_SDK_ROOT")
val releaseApk = layout.buildDirectory.file("outputs/apk/release/app-release.apk")
val verifyReleaseApk = tasks.register("verifyReleaseApk") {
    group = "verification"
    description = "Verify the final release APK signature and pinned certificate."
    notCompatibleWithConfigurationCache("APK verification must execute against the freshly built artifact.")
    doLast {
        val apk = releaseApk.get().asFile
        check(apk.isFile) { "Release APK is missing; cannot verify signature." }
        val sdk = File(requireNotNull(sdkLocation) { "Android SDK location is missing." })
        val buildTools = File(sdk, "build-tools").listFiles().orEmpty()
            .filter { it.isDirectory && File(it, "lib/apksigner.jar").isFile }
            .maxByOrNull { it.name }
            ?: error("Android SDK apksigner is unavailable.")
        val javaExe = File(System.getProperty("java.home"), "bin/" +
            if (System.getProperty("os.name").startsWith("Windows")) "java.exe" else "java")
        val process = ProcessBuilder(javaExe.absolutePath, "-jar",
            File(buildTools, "lib/apksigner.jar").absolutePath,
            "verify", "--print-certs", apk.absolutePath).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        check(process.waitFor() == 0) { "Release APK signature verification failed." }
        val signers = Regex("(?i)SHA-256 digest:\\s*([0-9a-f:]+)").findAll(output)
            .map { it.groupValues[1].replace(":", "").lowercase() }.toSet()
        val expected = releaseFingerprintFile.readText().trim().replace(":", "").lowercase()
        check(signers == setOf(expected)) { "Release APK signer does not match the project fingerprint." }
    }
}
tasks.configureEach {
    if (name == "assembleRelease") finalizedBy(verifyReleaseApk)
}

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
    // The assembly layer pairs the self-update service with the shared HTTP client (ADR-0017).
    implementation(project(":data:update"))
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
