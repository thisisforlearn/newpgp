// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Vaibhav - https://github.com/thisisforlearn
package com.newpgp.keyboard

/**
 * STUB — NOT CONNECTED TO THE RUST CORE IN THIS BUILD.
 *
 * This class exists so the wiring point is explicit and honest. Calling [encrypt]
 * always fails with a visible, user-facing error; no text is sent anywhere.
 *
 * Phase 2 integration plan (not implemented here):
 *  1. Build `newpgp-core` for Android targets (aarch64-linux-android,
 *     armv7-linux-androideabi, x86_64-linux-android) with UniFFI scaffolding enabled.
 *  2. Add the generated Kotlin bindings plus the `.so` libraries under
 *     `app/src/main/jniLibs/<abi>/` and a Gradle dependency on the bindings module.
 *  3. Replace the body of [encrypt] with a call such as
 *     `newpgp.core.Pgp.encryptArmored(plaintext, recipientKeyFingerprint)`,
 *     bridging errors into `Result.failure` and zeroing transient buffers where possible.
 *  4. Surface recipient/key selection in [SettingsActivity] before enabling the bridge
 *     in [PgpBridgeProvider], then flip `ENABLE_DEMO_PGP_BRIDGE` off permanently.
 *
 * Until those steps land this bridge must keep failing closed rather than returning
 * fake ciphertext.
 */
class NativePgpBridge : PgpBridge {
    override val displayName: String = "NewPGP native core (not connected in this build)"

    override fun encrypt(plaintext: String): Result<String> =
        Result.failure(UnsupportedOperationException(NOT_CONNECTED_MESSAGE))

    companion object {
        const val NOT_CONNECTED_MESSAGE =
            "the native PGP bridge is not connected in this build yet"
    }
}
