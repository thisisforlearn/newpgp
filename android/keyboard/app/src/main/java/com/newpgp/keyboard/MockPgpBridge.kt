// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Vaibhav - https://github.com/thisisforlearn
package com.newpgp.keyboard

/**
 * Deterministic, offline bridge used by unit tests (and by optional demo builds via
 * [PgpBridgeProvider]). It produces clearly-marked fake armor — it is NOT real PGP.
 */
class MockPgpBridge : PgpBridge {
    override val displayName: String = "NewPGP mock bridge (not real encryption)"

    override fun encrypt(plaintext: String): Result<String> {
        if (plaintext.isBlank()) {
            return Result.failure(IllegalArgumentException("Nothing to encrypt"))
        }
        val payload = plaintext.toByteArray(Charsets.UTF_8).joinToString("") { byte ->
            (byte.toInt() and 0xFF).toString(16).padStart(2, '0').uppercase()
        }
        return Result.success(
            "-----BEGIN PGP MESSAGE-----\n" +
                "Version: NewPGP Mock (tests only)\n\n" +
                payload.chunked(64).joinToString("\n") + "\n" +
                "=MOCK\n" +
                "-----END PGP MESSAGE-----\n"
        )
    }
}
