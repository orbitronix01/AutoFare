import java.util.Properties

plugins {
    // AGP 9 applies the Kotlin Android plugin itself; declaring
    // org.jetbrains.kotlin.android as well is now a hard error.
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Signing details live outside the project. The same key must sign every future
// release: if it changes, drivers have to uninstall before they can update.
val keystorePropsFile = rootProject.file("../keystore/keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) keystorePropsFile.inputStream().use { load(it) }
}

android {
    namespace = "jo.autofare"
    // 37 is a floor set by compose-foundation 1.12, not a behaviour change:
    // targetSdk stays at 35 so nothing about how the app runs on a driver's
    // phone changes with this upgrade.
    compileSdk = 37

    defaultConfig {
        applicationId = "jo.autofare"
        minSdk = 26
        targetSdk = 35
        versionCode = 4
        versionName = "0.4-auto"
    }

    signingConfigs {
        if (keystoreProps.containsKey("AUTOFARE_STORE_FILE")) {
            create("release") {
                storeFile = file(keystoreProps.getProperty("AUTOFARE_STORE_FILE"))
                storePassword = keystoreProps.getProperty("AUTOFARE_STORE_PASSWORD")
                keyAlias = keystoreProps.getProperty("AUTOFARE_KEY_ALIAS")
                keyPassword = keystoreProps.getProperty("AUTOFARE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            // left off deliberately: the accessibility service and the screen dump
            // walk classes by name, and shrinking is a needless risk for a build
            // whose whole job is to report what it sees
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        // off by default since AGP 8; the settings screen reads VERSION_NAME
        // from it rather than carrying a hand-typed version that goes stale
        buildConfig = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        // Opted in once here rather than annotating every call site: the whole UI
        // layer is built on these, and 1.5's Expressive surface is still marked
        // experimental until material3 1.5.0 goes stable.
        freeCompilerArgs.addAll(
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api",
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3ExpressiveApi",
        )
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.09.00")
    implementation(composeBom)

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.5")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    // Pinned past the BOM: every 2026 BOM still maps to material3 1.4.0, and the
    // Expressive components (ButtonGroup, LoadingIndicator, MaterialShapes, the
    // expressive motion scheme) only exist on the 1.5.x line.
    implementation("androidx.compose.material3:material3:1.5.0-beta02")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    // The detectors, the rule engine and Numbers are all pure Kotlin, and between
    // them they decide which rides a driver's money comes from. They are tested
    // against verbatim screens from a real shift — see test/resources/captures —
    // on the JVM, with no device needed.
    testImplementation("junit:junit:4.13.2")
}
