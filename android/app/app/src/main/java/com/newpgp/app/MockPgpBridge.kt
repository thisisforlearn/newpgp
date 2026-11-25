// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Vaibhav - https://github.com/thisisforlearn
package com.newpgp.app

/**
 * Deterministic, offline bridge used by JVM unit tests and by optional local
 * demo builds. It produces clearly-marked FAKE armor — it is NOT real PGP and
 * offers no confidentiality. Never enable it in a released build.
 */
class MockPgpBridge : PgpBridge {
    override val displayName: String = "NewPGP demo bridge (TEST DATA - not real encryption)"
    override val isRealCrypto: Boolean = false

    private val lock = Any()
    private var identity: Identity? = null

    // Clearly fake contacts so the People screen is explorable without a network.
    private val contacts = mutableListOf(
        Contact("Ada (demo)", "ada@example.test", fingerprintFor("ada@example.test")),
        Contact("Grace (demo)", "grace@example.test", fingerprintFor("grace@example.test")),
    )

    override fun createIdentity(email: String, displayName: String): Result<Identity> {
        val normalizedEmail = email.trim().lowercase()
        val name = displayName.trim()
        if (!isValidEmail(normalizedEmail)) {
            return Result.failure(IllegalArgumentException("Enter a valid email address"))
        }
        if (name.isEmpty()) {
            return Result.failure(IllegalArgumentException("Enter your name"))
        }
        val created = Identity(
            displayName = name,
            email = normalizedEmail,
            fingerprint = fingerprintFor(normalizedEmail),
            publicKeyArmored = publicKeyFor(normalizedEmail),
        )
        synchronized(lock) { identity = created }
        return Result.success(created)
    }

    override fun publicKey(): Result<String> {
        val current = synchronized(lock) { identity }
        return if (current != null) {
            Result.success(current.publicKeyArmored)
        } else {
            Result.failure(IllegalStateException("Create your key first"))
        }
    }

    override fun contacts(): Result<List<Contact>> = synchronized(lock) {
        Result.success(contacts.toList())
    }

    override fun discoverKey(email: String): Result<Contact> {
        val normalizedEmail = email.trim().lowercase()
        if (!isValidEmail(normalizedEmail)) {
            return Result.failure(IllegalArgumentException("Enter a valid email address"))
        }
        return synchronized(lock) {
            val existing = contacts.firstOrNull { it.email.equals(normalizedEmail, ignoreCase = true) }
            if (existing != null) {
                Result.success(existing)
            } else {
                val found = Contact(
                    displayName = "Discovered (demo)",
                    email = normalizedEmail,
                    fingerprint = fingerprintFor(normalizedEmail),
                )
                contacts.add(found)
                Result.success(found)
            }
        }
    }

    override fun encrypt(plaintext: String, recipients: List<String>): Result<String> {
        if (plaintext.isBlank()) {
            return Result.failure(IllegalArgumentException("Nothing to encrypt"))
        }
        if (recipients.isEmpty()) {
            return Result.failure(IllegalArgumentException("Add at least one recipient"))
        }
        val payload = plaintext.toByteArray(Charsets.UTF_8).joinToString("") { byte ->
            (byte.toInt() and 0xFF).toString(16).padStart(2, '0').uppercase()
        }
        val header = buildString {
            append("-----BEGIN PGP MESSAGE-----\n")
            append("Version: $MOCK_MARKER (tests only)\n")
            synchronized(lock) { identity }?.let { append("Comment: signer=${it.fingerprint}\n") }
            append('\n')
        }
        return Result.success(
            header +
                payload.chunked(64).joinToString("\n") + "\n" +
                "=MOCK\n-----END PGP MESSAGE-----\n",
        )
    }

    override fun decrypt(armored: String): Result<DecryptionResult> {
        if (!armored.contains(MOCK_MARKER)) {
            return Result.failure(
                IllegalArgumentException("This is not a NewPGP demo message; no plaintext was produced"),
            )
        }
        val lines = armored.lines()
        val payload = lines
            .dropWhile { it.isNotBlank() } // skip armor headers
            .drop(1) // skip the blank separator line
            .takeWhile { it != "=MOCK" }
            .joinToString("")
        val bytes = payload.chunked(2).map { chunk ->
            (chunk.toInt(16) and 0xFF).toByte()
        }.toByteArray()
        val plaintext = String(bytes, Charsets.UTF_8)
        val signer = lines.firstOrNull { it.startsWith("Comment: signer=") }
            ?.removePrefix("Comment: signer=")
        val status = if (signer != null) SignatureStatus.SIGNED_UNVERIFIED else SignatureStatus.NOT_SIGNED
        return Result.success(DecryptionResult(plaintext, status, signer))
    }

    override fun compose(text: String, recipients: List<String>, pgpOnly: Boolean): Result<String> {
        val encrypted = encrypt(text, recipients)
        if (encrypted.isFailure) {
            return Result.failure(encrypted.exceptionOrNull() ?: IllegalStateException("Encryption failed"))
        }
        val armor = encrypted.getOrThrow()
        return Result.success(if (pgpOnly) armor else "$text\n\n$armor")
    }

    override fun safetyNumber(contact: Contact): Result<String> =
        Result.success(safetyNumberFor(contact.fingerprint))

    override fun verifySafetyNumber(contact: Contact, enteredNumber: String): Result<SafetyNumberResult> {
        val canonical = safetyNumberFor(contact.fingerprint)
        if (normalize(enteredNumber).isEmpty()) {
            return Result.failure(IllegalArgumentException("Type the safety number first"))
        }
        val matches = normalize(enteredNumber) == normalize(canonical)
        return Result.success(SafetyNumberResult(contact.copy(verified = matches), canonical, matches))
    }

    companion object {
        /** Visible marker so mock output can never be mistaken for real armor. */
        const val MOCK_MARKER: String = "NewPGP MOCK"

        fun isValidEmail(value: String): Boolean {
            val trimmed = value.trim()
            return trimmed.contains('@') && !trimmed.startsWith('@') && !trimmed.endsWith('@')
        }

        fun fingerprintFor(email: String): String {
            val bytes = email.trim().lowercase().toByteArray(Charsets.UTF_8)
            return (0 until 40).joinToString("") { index ->
                (bytes[index % bytes.size].toInt() and 0xFF).toString(16).padStart(2, '0').uppercase()
            }.take(40)
        }

        fun safetyNumberFor(fingerprint: String): String =
            fingerprint.filter { it.isLetterOrDigit() }.uppercase().chunked(5).joinToString(" ")

        private fun normalize(value: String): String =
            value.filter { it.isLetterOrDigit() }.uppercase()

        private fun publicKeyFor(email: String): String =
            "-----BEGIN PGP PUBLIC KEY BLOCK-----\n" +
                "Comment: $MOCK_MARKER (tests only)\n\n" +
                "MOCKKEY:${fingerprintFor(email)}\n" +
                "-----END PGP PUBLIC KEY BLOCK-----\n"
    }
}
