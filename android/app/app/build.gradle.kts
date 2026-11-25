plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.newpgp.app"
    compileSdk = 36
    // Keep this in sync with the SDK package CI installs (platforms;android-36,
    // build-tools;36.0.0).
    buildToolsVersion = "36.0.0"

    defaultConfig {
        applicationId = "com.newpgp.app"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // The native Rust bridge is NOT wired up in this module. Ordinary builds
        // fail closed via NativePgpBridge. Flip this to true only for a local
        // UI walkthrough with clearly-labelled fake armor; never in releases.
        buildConfigField("boolean", "ENABLE_DEMO_PGP_BRIDGE", "false")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        viewBinding = false
        // AGP 8 disables BuildConfig by default; BridgeProvider reads the demo flag.
        buildConfig = true
    }

    testOptions {
        // JVM unit tests never touch Android framework methods.
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    // Compose 1.12+ requires compileSdk 37 / AGP 9. This BOM is the last line
    // that stays compatible with compileSdk 36 + AGP 8.11.1 + Kotlin 2.2.0.
    val composeBom = platform("androidx.compose:compose-bom:2025.11.00")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.activity:activity-compose")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.1")
    debugImplementation("androidx.compose.ui:ui-tooling")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.1")
}
