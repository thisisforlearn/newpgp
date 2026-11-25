// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Vaibhav - https://github.com/thisisforlearn
package com.newpgp.app

/**
 * STUB - THE RUST CORE IS NOT CONNECTED IN THIS BUILD.
 *
 * Every call fails closed with a visible, user-facing error. No text is
 * returned as "ciphertext" and nothing is sent anywhere. This class exists so
 * the wiring point is explicit and the app is honest about its status.
 *
 * Phase 2 integration plan (not implemented here):
 *  1. Build `newpgp-core` for Android targets (aarch64-linux-android,
 *     armv7-linux-androideabi, x86_64-linux-android) with UniFFI scaffolding.
 *  2. Add the generated Kotlin bindings and `.so` files under
 *     `app/src/main/jniLibs/<abi>/`, plus the bindings module as a dependency.
 *  3. Keep private key material in the Android Keystore: generate or import a
 *     non-exportable key with KeyGenParameterSpec and let the Rust core use it
 *     through a JNI handle, rather than copying secret bytes into the JVM heap.
 *  4. Replace each body below with calls into the generated API. For example,
 *     `encrypt` becomes a call to an armored encrypt function taking the
 *     plaintext and recipient fingerprints; map failures into `Result.failure`
 *     and avoid retaining plaintext copies. Run crypto off the main thread.
 *  5. Surface identity/recipient selection in Compose, then switch
 *     [BridgeProvider.current] to this bridge and set [isRealCrypto] to true.
 *
 * Until those steps land this bridge must keep failing closed.
 */
class NativePgpBridge : PgpBridge {
    override val displayName: String = "NewPGP native core (not connected in this build)"
    override val isRealCrypto: Boolean = false

    override fun createIdentity(email: String, displayName: String): Result<Identity> = unavailable()
    override fun publicKey(): Result<String> = unavailable()
    override fun contacts(): Result<List<Contact>> = unavailable()
    override fun discoverKey(email: String): Result<Contact> = unavailable()

    override fun encrypt(plaintext: String, recipients: List<String>): Result<String> = unavailable()

    override fun decrypt(armored: String): Result<DecryptionResult> = unavailable()

    override fun compose(text: String, recipients: List<String>, pgpOnly: Boolean): Result<String> =
        unavailable()

    override fun safetyNumber(contact: Contact): Result<String> = unavailable()

    override fun verifySafetyNumber(contact: Contact, enteredNumber: String): Result<SafetyNumberResult> =
        unavailable()

    private fun <T> unavailable(): Result<T> =
        Result.failure(UnsupportedOperationException(NOT_CONNECTED_MESSAGE))

    companion object {
        const val NOT_CONNECTED_MESSAGE: String =
            "the native PGP bridge is not connected in this build yet"
    }
}
