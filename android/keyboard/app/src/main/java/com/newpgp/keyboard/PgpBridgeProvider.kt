// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Vaibhav - https://github.com/thisisforlearn
package com.newpgp.keyboard

private const val NATIVE_LIBRARY_NAME = "newpgp_mobile"

/**
 * Selects the bridge used by the service. Tests construct bridges directly and can
 * inject [libraryLoader] to simulate a build with or without the bundled `.so`.
 */
object PgpBridgeProvider {

    /**
     * Injectable so JVM tests can prove both outcomes. Declared before [current] so
     * the default bridge sees a fully initialised loader.
     */
    internal var libraryLoader: () -> Unit = { System.loadLibrary(NATIVE_LIBRARY_NAME) }

    /**
     * The real bridge whenever the library is present. The demo flag stays off in
     * normal builds and only exercises the UI with fake armor.
     */
    @Volatile
    var current: PgpBridge = createBridge()

    /** Loads the library, then selects the native bridge (or an honest failing one). */
    fun createBridge(): PgpBridge {
        val loaded = try {
            libraryLoader()
            true
        } catch (e: UnsatisfiedLinkError) {
            false
        } catch (e: SecurityException) {
            false
        }
        // Read reflectively so the demo flag can be removed from Gradle without
        // breaking the real-crypto wiring; absent means "off" (safe default).
        val demoEnabled = try {
            BuildConfig::class.java.getField("ENABLE_DEMO_PGP_BRIDGE").getBoolean(null)
        } catch (e: Exception) {
            false
        }
        return if (demoEnabled) {
            MockPgpBridge()
        } else {
            NativePgpBridge(nativeAvailable = loaded)
        }
    }

    /** Re-selects [current] after a test (or host) changes [libraryLoader]. */
    fun reload() {
        current = createBridge()
    }
}
