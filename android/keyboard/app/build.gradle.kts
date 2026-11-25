plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Release signing is optional. CI exposes these as ORG_GRADLE_PROJECT_* env
// vars, which Gradle maps to project properties. Debug builds and local builds
// have none of them, so configuration must succeed without any secrets.
val uploadStoreFile = providers.gradleProperty("NEWPGP_UPLOAD_STORE_FILE").orNull?.takeIf { it.isNotBlank() }
val uploadStorePassword = providers.gradleProperty("NEWPGP_UPLOAD_STORE_PASSWORD").orNull?.takeIf { it.isNotBlank() }
val uploadKeyAlias = providers.gradleProperty("NEWPGP_UPLOAD_KEY_ALIAS").orNull?.takeIf { it.isNotBlank() }
val uploadKeyPassword = providers.gradleProperty("NEWPGP_UPLOAD_KEY_PASSWORD").orNull?.takeIf { it.isNotBlank() }
val hasUploadSigning =
    uploadStoreFile != null && uploadStorePassword != null && uploadKeyAlias != null && uploadKeyPassword != null

android {
    namespace = "com.newpgp.keyboard"
    compileSdk = 36
    // Keep this in sync with the SDK package installed by the CI workflow.
    buildToolsVersion = "36.0.0"

    signingConfigs {
        if (hasUploadSigning) {
            create("release") {
                // Path is relative to the Gradle root project (android/keyboard).
                storeFile = rootProject.file(uploadStoreFile!!)
                storePassword = uploadStorePassword!!
                keyAlias = uploadKeyAlias!!
                keyPassword = uploadKeyPassword!!
            }
        }
    }

    defaultConfig {
        applicationId = "com.newpgp.keyboard"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // The native Rust bridge is NOT connected in this build. Tests use MockPgpBridge
        // directly; the app intentionally reports "unavailable" until phase 2 lands.
        buildConfigField("boolean", "ENABLE_DEMO_PGP_BRIDGE", "false")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (hasUploadSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
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
        viewBinding = true
        buildConfig = true
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.appcompat:appcompat:1.8.0")
    implementation("com.google.android.material:material:1.12.0")

    testImplementation("junit:junit:4.13.2")
}
