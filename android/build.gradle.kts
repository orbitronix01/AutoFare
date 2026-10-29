// Material 3 Expressive (material3 1.5.x) pulls in compose-foundation 1.12, which
// refuses to build on anything below AGP 9.1 / compileSdk 37. That is what sets
// every version in here — see app/build.gradle.kts for the matching SDK levels.
plugins {
    id("com.android.application") version "9.1.1" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.20" apply false
}
