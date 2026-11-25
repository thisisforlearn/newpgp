// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Vaibhav - https://github.com/thisisforlearn
package com.newpgp.app

/**
 * The only channel through which app text may reach encryption code.
 *
 * PRIVACY CONTRACT: this is the sole declared egress for text and keys. Nothing
 * in the UI logs plaintext, writes it to disk, or sends it to a service; all
 * network access (if any) stays behind [discoverKey] in a concrete bridge.
 * Implementations must never log plaintext, private keys, or results.
 */
interface PgpBridge {
    /** Human-readable name, shown in the UI so users know what is handling data. */
    val displayName: String

    /** True only when a real OpenPGP implementation is connected. */
    val isRealCrypto: Boolean

    /** Creates a local identity. Private key material must never leave the device. */
    fun createIdentity(email: String, displayName: String): Result<Identity>

    /** Returns the ASCII-armored public key, safe to share. */
    fun publicKey(): Result<String>

    /** Returns the locally known contacts. */
    fun contacts(): Result<List<Contact>>

    /** Looks up a public key by email (e.g. via WKD over HTTPS). */
    fun discoverKey(email: String): Result<Contact>

    /** Encrypts [plaintext] for [recipients] and returns ASCII-armored output. */
    fun encrypt(plaintext: String, recipients: List<String>): Result<String>

    /** Decrypts ASCII-armored input and reports what could be verified. */
    fun decrypt(armored: String): Result<DecryptionResult>

    /**
     * Builds the final text a user can hand to another app.
     *
     * With [pgpOnly] = false the result contains the original text followed by
     * the armored block ("send both"). With [pgpOnly] = true it contains only
     * the armored block, so no plaintext is included.
     */
    fun compose(text: String, recipients: List<String>, pgpOnly: Boolean): Result<String>

    /** Returns the canonical, human-readable safety number for a contact. */
    fun safetyNumber(contact: Contact): Result<String>

    /** Compares a safety number read out-of-band with the contact's own number. */
    fun verifySafetyNumber(contact: Contact, enteredNumber: String): Result<SafetyNumberResult>
}

/** Public identity created by [PgpBridge.createIdentity]. */
data class Identity(
    val displayName: String,
    val email: String,
    val fingerprint: String,
    val publicKeyArmored: String,
)

/** A remote key the user may encrypt to. */
data class Contact(
    val displayName: String,
    val email: String,
    val fingerprint: String,
    val verified: Boolean = false,
)

/** What the bridge could establish about a signature. */
enum class SignatureStatus {
    VERIFIED,
    SIGNED_UNVERIFIED,
    NOT_SIGNED,
    UNKNOWN,
}

/** Result of [PgpBridge.decrypt]. */
data class DecryptionResult(
    val plaintext: String,
    val signatureStatus: SignatureStatus,
    val signerFingerprint: String? = null,
)

/** Result of [PgpBridge.verifySafetyNumber]. */
data class SafetyNumberResult(
    val contact: Contact,
    val canonicalNumber: String,
    val matches: Boolean,
)
