plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    // Both of these must be the real app's package. `namespace` is what the R class
    // and therefore `viewIdResourceName` are built from, and that string is exactly
    // what PetraDetector looks up: com.PetraRide_Captain:id/accept_button
    namespace = "com.PetraRide_Captain"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.PetraRide_Captain"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "harness"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions { jvmTarget = "17" }
}

