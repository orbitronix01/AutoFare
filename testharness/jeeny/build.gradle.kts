plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    // Jeeny's driver app ships under its Easy Taxi ancestry. JeenyDetector matches
    // on this package and then on the words in the sheet, since the real app
    // exposes no view ids at all on its offer.
    namespace = "me.com.easytaxista"
    compileSdk = 35

    defaultConfig {
        applicationId = "me.com.easytaxista"
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

