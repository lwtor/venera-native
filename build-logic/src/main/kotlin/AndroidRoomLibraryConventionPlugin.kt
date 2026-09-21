import com.android.build.api.dsl.LibraryExtension
import com.google.devtools.ksp.gradle.KspExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

/**
 * An Android library that runs annotation processing through KSP.
 *
 * Only modules that really need KSP apply this: adding the KSP plugin to every library would slow
 * the whole build down. The Room schema is exported to a versioned directory inside the module so
 * migration tests have a baseline to compare against.
 */
class AndroidRoomLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("com.android.library")
        pluginManager.apply("com.google.devtools.ksp")

        extensions.configure<LibraryExtension> {
            configureVeneraAndroidLibrary(compose = false)
        }
        extensions.configure<KspExtension> {
            arg("room.schemaLocation", "$projectDir/schemas")
        }
    }
}
