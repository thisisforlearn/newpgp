plugins {
    id("com.android.application") version "8.11.1" apply false
    id("org.jetbrains.kotlin.android") version "2.2.0" apply false
    // The Compose compiler ships with Kotlin since 2.0; its version MUST match
    // the Kotlin plugin version above.
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.0" apply false
}

tasks.register<Delete>("clean") {
    delete(layout.buildDirectory)
}
