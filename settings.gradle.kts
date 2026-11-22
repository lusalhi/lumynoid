pluginManagement {
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

rootProject.name = "lumynoid"

include(":core", ":llm", ":data", ":userland", ":app")
include(":userland-check")
project(":userland-check").projectDir = file("tools/userland-check")
