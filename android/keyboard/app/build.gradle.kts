import java.util.concurrent.TimeUnit

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// The Rust core lives at the repository root, two directories above this
// Gradle root project (android/keyboard). Nothing here is machine-specific.
val newpgpRepoRoot: File = rootProject.projectDir.parentFile.parentFile
val newpgpCargoManifest: File = newpgpRepoRoot.resolve("crates/newpgp-mobile/Cargo.toml")
val newpgpNdkOutput = layout.buildDirectory.dir("newpgp/jniLibs")

/** Local Kotlin-only builds keep working when cargo-ndk is not installed. */
fun cargoNdkAvailable(): Boolean = try {
    val probe = ProcessBuilder("cargo", "ndk", "--version")
        .redirectErrorStream(true)
        .start()
    probe.waitFor(30, TimeUnit.SECONDS) && probe.exitValue() == 0
} catch (_: Exception) {
    false
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
        versionCode = 3
        versionName = "0.1.4"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // The native Rust bridge is NOT connected in this build. Tests use MockPgpBridge
        // directly; the app intentionally reports "unavailable" until phase 2 lands.
        buildConfigField("boolean", "ENABLE_DEMO_PGP_BRIDGE", "false")

        // Only ship ABIs for which cargo-ndk produces the Rust static library.
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }

        externalNativeBuild {
            cmake {
                // AGP supplies ANDROID_ABI/ANDROID_PLATFORM itself per ABI; do
                // not hardcode them here. Use abiFilters above instead.
                arguments += listOf(
                    "-DANDROID_STL=c++_shared",
                    "-DNEWPGP_RUST_TARGET_DIR=${newpgpRepoRoot.resolve("target").absolutePath}"
                )
            }
        }
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

    // The C++ JNI shim is the packaged bridge: libnewpgp_mobile.so.
    // cargo-ndk's own output stays in build/ and feeds the staticlib imported
    // below; it is deliberately not a second jniLibs source (same file name).
    sourceSets {
        getByName("main") {
            jniLibs.setSrcDirs(listOf(file("src/main/jniLibs")))
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")

    // Required by LiteRtInterpreterAdapter.kt (AI agent).
    implementation("org.tensorflow:tensorflow-lite:2.17.0")

    testImplementation("junit:junit:4.13.2")
}

// Build the Rust half before CMake looks for its static libraries. The task
// is a no-op when cargo-ndk is absent so unit-test-only builds stay working.
val buildNewpgpRust by tasks.registering(Exec::class) {
    description = "Build crates/newpgp-mobile for Android ABIs using cargo-ndk."
    group = "newpgp"
    onlyIf { cargoNdkAvailable() }
    workingDir = newpgpRepoRoot
    commandLine(
        "cargo", "ndk",
        "-t", "armeabi-v7a", "-t", "arm64-v8a", "-t", "x86_64",
        "-o", newpgpNdkOutput.get().asFile.absolutePath,
        "build", "--release",
        "--manifest-path", newpgpCargoManifest.absolutePath
    )
}

tasks.matching {
    it.name.startsWith("externalNativeBuild") || it.name.startsWith("configureCMake")
}.configureEach {
    dependsOn(buildNewpgpRust)
}
