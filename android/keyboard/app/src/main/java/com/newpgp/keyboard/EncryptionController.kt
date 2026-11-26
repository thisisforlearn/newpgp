// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Vaibhav - https://github.com/thisisforlearn
package com.newpgp.keyboard

/**
 * Bridge-agnostic formatting for the two encryption modes, kept free of Android APIs.
 *
 * [recipientsCsv] defaults to empty (the native bridge self-encrypts to the local
 * identity), [sign] defaults to true so a password-protected key's passphrase is
 * actually used, and [passwordProvider] defaults to "". The IME supplies the saved
 * key password through [passwordProvider]; no password is ever logged.
 */
class EncryptionController(
    private val bridge: PgpBridge,
    private val recipientsCsv: String = "",
    private val sign: Boolean = true,
    private val passwordProvider: () -> String = { "" }
) {

    /** Explicit mode: replace the current text with the armored block. */
    fun encrypt(plaintext: String): Result<String> = encrypt(plaintext, passwordProvider())

    /** Explicit mode with an override password (used by the shield action). */
    fun encrypt(plaintext: String, password: String): Result<String> =
        bridge.encrypt(plaintext, recipientsCsv, sign, password)

    /** Auto-append mode: keep the original text and add the armored block after a blank line. */
    fun buildAutoAppendText(original: String, armored: String): String =
        original.trimEnd() + "\n\n" + armored.trim().trimEnd() + "\n"

    /** Safety number used in the post-encryption toast. */
    fun safetyNumber(email: String = ""): Result<String> = bridge.safetyNumber(email)

    /** Fingerprint cached by the bridge after key creation. */
    fun fingerprint(): String? = bridge.fingerprint()

    /** Safe, non-sensitive status text for a failed encryption attempt. */
    fun describeFailure(error: Throwable): String =
        error.message?.takeIf { it.isNotBlank() } ?: "unknown encryption error"
}

/**
 * Android-free shield workflow shared by the IME and its JVM tests.
 *
 * The plaintext is encrypted first and ONLY a successful, non-blank armor block is
 * committed. A bridge failure (or a stale input session) leaves the editor exactly
 * as it was, so no half-written ciphertext or empty text can appear.
 */
class ShieldEncryptor(
    private val text: ImeTextController,
    private val encryption: EncryptionController
) {
    /**
     * @param wholeFieldLength non-null only for the explicit "Encrypt whole message"
     *   action; null means replace the composing region or the active selection.
     */
    fun run(
        ic: TextEditConnection,
        plaintext: String,
        password: String,
        wholeFieldLength: Int? = null
    ): Result<String> {
        if (plaintext.isBlank()) {
            return Result.failure(IllegalArgumentException("Nothing to encrypt"))
        }
        val generation = text.generation
        val attempt = encryption.encrypt(plaintext, password)
        val armored = attempt.getOrNull()
            ?: return Result.failure(SecurityException(failureMessage(attempt.exceptionOrNull())))
        if (armored.isBlank()) {
            return Result.failure(SecurityException(NOTHING_INSERTED_MESSAGE))
        }
        if (!text.isCurrentGeneration(generation)) {
            return Result.failure(IllegalStateException(SESSION_CHANGED_MESSAGE))
        }
        if (wholeFieldLength != null) {
            text.replaceAllWithArmored(ic, armored, wholeFieldLength)
        } else {
            text.replaceComposingWithArmored(ic, armored)
        }
        return Result.success(armored)
    }

    /**
     * Converts a bridge failure into non-sensitive, plain-language copy. The raw
     * exception text is never echoed: the only detail worth preserving is the
     * dedicated "engine missing" case whose exact copy is owned by [NativePgpBridge].
     * Every failure reassures the user that nothing was inserted, so their typed
     * text is still in the field.
     */
    private fun failureMessage(cause: Throwable?): String =
        if (cause?.message == NativePgpBridge.MISSING_ENGINE_MESSAGE) {
            NativePgpBridge.MISSING_ENGINE_MESSAGE + NOTHING_INSERTED_SUFFIX
        } else {
            NOTHING_INSERTED_MESSAGE
        }

    private companion object {
        const val NOTHING_INSERTED_MESSAGE = "Encryption failed; nothing was inserted"
        const val NOTHING_INSERTED_SUFFIX = "; nothing was inserted"
        const val SESSION_CHANGED_MESSAGE = "Input session changed; nothing was inserted"
    }
}
