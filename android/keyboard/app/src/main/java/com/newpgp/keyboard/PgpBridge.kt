// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Vaibhav - https://github.com/thisisforlearn
package com.newpgp.keyboard

/**
 * The only channel through which the IME may hand text to encryption code.
 *
 * PRIVACY CONTRACT: this is the sole declared egress for text from the keyboard.
 * The IME never logs keystrokes, never uses analytics, and never opens a network
 * connection. All composing state stays in memory for the lifetime of the input session.
 * Implementations must never log plaintext, private keys, or results.
 *
 * The richer overloads have default implementations so that existing simple bridges
 * (for example the demo/mock bridge) keep working unchanged. The native bridge
 * overrides them with real calls into [NewPgpNative].
 */
interface PgpBridge {
    /** Human-readable name, shown in status messages for transparency. */
    val displayName: String

    /** True only when the private engine actually loaded; never a claim made by a stub. */
    val isRealCrypto: Boolean get() = false

    /** Hands the app-private files directory to the native store before first use. */
    fun initialize(filesDirAbsolutePath: String) = Unit

    /**
     * Encrypts [plaintext] and returns ASCII-armored OpenPGP output.
     * Implementations must never log the plaintext or the result.
     */
    fun encrypt(plaintext: String): Result<String>

    /**
     * Full native contract. [recipientsCsv] is a comma-separated recipient list
     * (empty means "encrypt to the local identity"), [sign] adds a signature and
     * [password] unlocks the private key when it is password protected.
     */
    fun encrypt(
        plaintext: String,
        recipientsCsv: String,
        sign: Boolean,
        password: String
    ): Result<String> = encrypt(plaintext)

    /** Creates a local identity; returns the native JSON result. */
    fun generateIdentity(name: String, email: String, password: String): Result<String> =
        Result.failure(UnsupportedOperationException("Key creation is unavailable"))

    /** ASCII-armored public key, safe to share. */
    fun publicKey(): Result<String> =
        Result.failure(UnsupportedOperationException("Public key is unavailable"))

    /** Decrypts armor; returns the native JSON result. */
    fun decrypt(armored: String, password: String): Result<String> =
        Result.failure(UnsupportedOperationException("Decryption is unavailable"))

    /** Canonical, human-readable safety number for an identity (blank = local identity). */
    fun safetyNumber(email: String): Result<String> =
        Result.failure(UnsupportedOperationException("Safety number is unavailable"))

    /** Fingerprint cached from key creation, when the bridge knows it. */
    fun fingerprint(): String? = null
}
