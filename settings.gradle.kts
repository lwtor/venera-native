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
    ":core:designsystem",
    ":core:model",
    ":core:network",
    ":data:source",
    ":feature:home",
    ":feature:reader",
    ":feature:sources",
    ":source:api",
    ":source:engine",
    ":source:network",
)
