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
        // libadb-android (on-device ADB for starting the recorder) is only published on JitPack.
        maven("https://jitpack.io") {
            content { includeGroupByRegex("com\\.github\\.MuntashirAkon.*") }
        }
    }
}
rootProject.name = "AzaynRecorder"
include(":app")
