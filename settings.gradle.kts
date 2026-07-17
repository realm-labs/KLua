pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
    }
}

rootProject.name = "KLua"

include(
    "klua-api",
    "klua-core",
    "klua-dap",
    "klua-debug",
    "klua-examples",
    "klua-jmh",
    "klua-kotlin",
    "klua-stdlib",
    "klua-tests",
    "klua-tools",
)
