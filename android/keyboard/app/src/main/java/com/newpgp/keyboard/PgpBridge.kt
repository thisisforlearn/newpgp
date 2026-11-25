// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Vaibhav - https://github.com/thisisforlearn
package com.newpgp.keyboard

/**
 * The only channel through which the IME may hand text to encryption code.
 *
 * PRIVACY CONTRACT: this is the sole declared egress for text from the keyboard.
 * The IME never logs keystrokes, never uses analytics, and never opens a network
 * connection. All composing state stays in memory for the lifetime of the input session.
 */
interface PgpBridge {
    /** Human-readable name, shown in status messages for transparency. */
    val displayName: String

    /**
     * Encrypts [plaintext] and returns ASCII-armored OpenPGP output.
     * Implementations must never log the plaintext or the result.
     */
    fun encrypt(plaintext: String): Result<String>
}
