// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Vaibhav - https://github.com/thisisforlearn
package com.newpgp.keyboard

/**
 * Kotlin declarations for the Rust `newpgp_mobile` cdylib JNI surface.
 *
 * The Rust side exports `Java_com_newpgp_keyboard_NewPgpNative_*`; these names must
 * stay in sync with the contract. Strings returned by [generateIdentity] and
 * [decrypt] are JSON. No plaintext or key material is ever logged here.
 */
object NewPgpNative {
    external fun init(filesDirAbsolutePath: String): String
    external fun version(): String
    external fun generateIdentity(name: String, email: String, password: String): String
    external fun publicKey(): String
    external fun encrypt(plaintext: String, recipientsCsv: String, sign: Boolean, password: String): String
    external fun decrypt(armored: String, password: String): String
    external fun safetyNumber(email: String): String
}
