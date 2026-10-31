pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
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

rootProject.name = "AutoFare"
include(":app")

// Fake Jeeny and Petra Ride for the emulator scenario test (scripts/e2e-rules.sh).
// Debug-only APKs on the real apps' package names: never on a driver's phone.
include(":fakeride:jeeny", ":fakeride:petra")
