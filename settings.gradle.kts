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
    ":core:database",
    ":core:archive",
    ":core:backup",
    ":core:designsystem",
    ":core:image",
    ":core:model",
    ":core:navigation",
    ":core:network",
    ":data:comic",
    ":data:collection",
    ":data:backup",
    ":data:download",
    ":data:history",
    ":data:local",
    ":data:search",
    ":data:settings",
    ":data:source",
    ":feature:details",
    ":feature:explore",
    ":feature:home",
    ":feature:library",
    ":feature:reader",
    ":feature:search",
    ":feature:sources",
    ":source:api",
    ":source:core",
    ":source:engine",
    ":source:network",
)
