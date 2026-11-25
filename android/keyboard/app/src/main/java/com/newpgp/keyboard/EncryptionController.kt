// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Vaibhav - https://github.com/thisisforlearn
package com.newpgp.keyboard

/** Bridge-agnostic formatting for the two encryption modes, kept free of Android APIs. */
class EncryptionController(private val bridge: PgpBridge) {

    /** Explicit mode: replace the current text with the armored block. */
    fun encrypt(plaintext: String): Result<String> = bridge.encrypt(plaintext)

    /** Auto-append mode: keep the original text and add the armored block after a blank line. */
    fun buildAutoAppendText(original: String, armored: String): String =
        original.trimEnd() + "\n\n" + armored.trim().trimEnd() + "\n"

    /** Safe, non-sensitive status text for a failed encryption attempt. */
    fun describeFailure(error: Throwable): String =
        error.message?.takeIf { it.isNotBlank() } ?: "unknown encryption error"
}
