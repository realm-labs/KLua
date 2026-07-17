pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        exclusiveContent {
            forRepository {
                maven {
                    name = "stagedKLua"
                    url = uri(providers.gradleProperty("kluaRepository").get())
                    metadataSources {
                        mavenPom()
                        artifact()
                    }
                }
            }
            filter {
                includeGroup("io.github.realmlabs.klua")
            }
        }
        mavenCentral()
    }
}

rootProject.name = "klua-release-consumer-smoke"
