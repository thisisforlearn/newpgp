// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Vaibhav - https://github.com/thisisforlearn
package com.newpgp.keyboard

/**
 * Real bridge to the Rust `newpgp_mobile` library through [NewPgpNative].
 *
 * [PgpBridgeProvider] loads the shared library and passes the result as
 * [nativeAvailable]. When the library is absent (or a required symbol is missing)
 * this bridge still fails closed with an honest, user-facing message: it never
 * returns fake ciphertext and never claims to be real crypto.
 *
 * All errors are converted to [Result.failure] with non-sensitive copy. Plaintext,
 * passwords and key material are never logged or embedded in an exception message.
 */
class NativePgpBridge internal constructor(
    private val nativeAvailable: Boolean = false
) : PgpBridge {

    @Volatile
    private var linkFailed = false

    @Volatile
    private var cachedFingerprint: String? = null

    @Volatile
    private var identityEmail: String = ""

    private val ready: Boolean get() = nativeAvailable && !linkFailed

    override val isRealCrypto: Boolean get() = ready

    override val displayName: String
        get() = if (ready) "NewPGP native core" else "NewPGP native core (engine missing)"

    override fun initialize(filesDirAbsolutePath: String) {
        if (!nativeAvailable) return
        try {
            NewPgpNative.init(filesDirAbsolutePath)
        } catch (e: UnsatisfiedLinkError) {
            linkFailed = true
        } catch (e: Exception) {
            linkFailed = true
        }
    }

    override fun encrypt(plaintext: String): Result<String> = encrypt(plaintext, "", false, "")

    override fun encrypt(
        plaintext: String,
        recipientsCsv: String,
        sign: Boolean,
        password: String
    ): Result<String> {
        if (!ready) return Result.failure(UnsupportedOperationException(MISSING_ENGINE_MESSAGE))
        return try {
            // The Rust core requires at least one recipient. With no recipient chosen
            // in the keyboard, encrypt to the local identity's own public key.
            val recipient = recipientsCsv.ifBlank { NewPgpNative.publicKey() }
            if (recipient.isBlank()) {
                return Result.failure(SecurityException(NO_IDENTITY_MESSAGE))
            }
            val armored = NewPgpNative.encrypt(plaintext, recipient, sign, password)
            if (armored.isBlank() || !armored.contains(ARMOR_HEADER)) {
                Result.failure(SecurityException(ENCRYPTION_FAILED_MESSAGE))
            } else {
                Result.success(armored)
            }
        } catch (e: UnsatisfiedLinkError) {
            linkFailed = true
            Result.failure(UnsupportedOperationException(MISSING_ENGINE_MESSAGE))
        } catch (e: Exception) {
            Result.failure(SecurityException(ENCRYPTION_FAILED_MESSAGE))
        }
    }

    override fun generateIdentity(name: String, email: String, password: String): Result<String> {
        if (!ready) return Result.failure(UnsupportedOperationException(MISSING_ENGINE_MESSAGE))
        return try {
            val json = NewPgpNative.generateIdentity(name, email, password)
            if (json.isBlank()) {
                Result.failure(SecurityException(IDENTITY_FAILED_MESSAGE))
            } else {
                identityEmail = email
                cachedFingerprint = extractJsonString(json, KEY_FINGERPRINT)
                Result.success(json)
            }
        } catch (e: UnsatisfiedLinkError) {
            linkFailed = true
            Result.failure(UnsupportedOperationException(MISSING_ENGINE_MESSAGE))
        } catch (e: Exception) {
            Result.failure(SecurityException(IDENTITY_FAILED_MESSAGE))
        }
    }

    override fun publicKey(): Result<String> {
        if (!ready) return Result.failure(UnsupportedOperationException(MISSING_ENGINE_MESSAGE))
        return try {
            // An empty string is a normal "no key yet" state in the Rust core.
            Result.success(NewPgpNative.publicKey())
        } catch (e: UnsatisfiedLinkError) {
            linkFailed = true
            Result.failure(UnsupportedOperationException(MISSING_ENGINE_MESSAGE))
        } catch (e: Exception) {
            Result.failure(SecurityException(IDENTITY_FAILED_MESSAGE))
        }
    }

    override fun decrypt(armored: String, password: String): Result<String> {
        if (!ready) return Result.failure(UnsupportedOperationException(MISSING_ENGINE_MESSAGE))
        return try {
            val json = NewPgpNative.decrypt(armored, password)
            if (json.isBlank()) {
                Result.failure(SecurityException(DECRYPTION_FAILED_MESSAGE))
            } else {
                Result.success(json)
            }
        } catch (e: UnsatisfiedLinkError) {
            linkFailed = true
            Result.failure(UnsupportedOperationException(MISSING_ENGINE_MESSAGE))
        } catch (e: Exception) {
            Result.failure(SecurityException(DECRYPTION_FAILED_MESSAGE))
        }
    }

    override fun safetyNumber(email: String): Result<String> {
        if (!ready) return Result.failure(UnsupportedOperationException(MISSING_ENGINE_MESSAGE))
        return try {
            val target = email.ifBlank { identityEmail }
            val number = NewPgpNative.safetyNumber(target)
            if (number.isBlank()) {
                Result.failure(SecurityException(SAFETY_NUMBER_FAILED_MESSAGE))
            } else {
                Result.success(number)
            }
        } catch (e: UnsatisfiedLinkError) {
            linkFailed = true
            Result.failure(UnsupportedOperationException(MISSING_ENGINE_MESSAGE))
        } catch (e: Exception) {
            Result.failure(SecurityException(SAFETY_NUMBER_FAILED_MESSAGE))
        }
    }

    override fun fingerprint(): String? = cachedFingerprint

    companion object {
        private const val ARMOR_HEADER = "-----BEGIN PGP MESSAGE-----"
        private const val KEY_FINGERPRINT = "fingerprint"

        private fun extractJsonString(json: String, key: String): String? =
            Regex("\"$key\"\\s*:\\s*\"([^\"]+)\"").find(json)?.groupValues?.getOrNull(1)

        /** Exact user-facing copy when the private engine is not in the APK. */
        const val MISSING_ENGINE_MESSAGE =
            "The private engine is missing from this build — reinstall the APK"

        const val ENCRYPTION_FAILED_MESSAGE = "Encryption failed; nothing was inserted"
        const val NO_IDENTITY_MESSAGE = "No key yet. Create a key in NewPGP settings first."
        const val IDENTITY_FAILED_MESSAGE = "Could not create the local key; nothing was inserted"
        const val DECRYPTION_FAILED_MESSAGE = "Could not decrypt this message"
        const val SAFETY_NUMBER_FAILED_MESSAGE = "Safety number is unavailable"

        /** Kept as a source-compatible alias; its copy is no longer "not connected". */
        @Deprecated("Use MISSING_ENGINE_MESSAGE", ReplaceWith("MISSING_ENGINE_MESSAGE"))
        const val NOT_CONNECTED_MESSAGE = MISSING_ENGINE_MESSAGE
    }
}
