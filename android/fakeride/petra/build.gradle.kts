// FAKE Petra Ride, for the emulator scenario test only. See fakeride/README.md.
//
// applicationId AND namespace are the real app's package. The namespace is what
// AAPT writes into the resource table, and so what a view id is reported under:
// only with both set does a view surface as com.PetraRide_Captain:id/... to the
// accessibility service, which is what the unmodified detectors match on.
plugins {
    id("com.android.application")
}

android {
    namespace = "com.PetraRide_Captain"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.PetraRide_Captain"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "fakeride"
    }

    sourceSets {
        getByName("main") {
            // the built-in Kotlin of AGP 9 compiles kotlin dirs, not java ones
            kotlin.directories.add("../shared/java")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

// Debug only: there is no release of a fake ride app, ever.
androidComponents {
    beforeVariants(selector().withBuildType("release")) { it.enable = false }
}
