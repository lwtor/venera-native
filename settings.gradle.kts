pluginManagement {
    includeBuild("build-logic")
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "VeneraNative"

include(
    ":app",
    ":core:common",
    ":core:designsystem",
    ":core:model",
    ":core:navigation",
    ":feature:home",
    ":source:api",
    ":source:engine",
)
