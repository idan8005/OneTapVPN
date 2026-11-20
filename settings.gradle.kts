pluginManagement {
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }
}
rootProject.name = "OneTapVPN"
include(":app", ":vpnLib")
project(":vpnLib").projectDir = file("third_party/OpenGateVPN/vpnLib")
