// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Vaibhav - https://github.com/thisisforlearn
package com.newpgp.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Recording bridge used to prove the full native arguments reach the egress point. */
private class RecordingBridge(
    private val result: Result<String> = Result.success("ARMOR")
) : PgpBridge {
    override val displayName: String = "recording"
    var plaintext: String? = null
    var recipientsCsv: String? = null
    var sign: Boolean? = null
    var password: String? = null
    var safetyEmail: String? = null

    override fun encrypt(plaintext: String): Result<String> =
        encrypt(plaintext, "", false, "")

    override fun encrypt(
        plaintext: String,
        recipientsCsv: String,
        sign: Boolean,
        password: String
    ): Result<String> {
        this.plaintext = plaintext
        this.recipientsCsv = recipientsCsv
        this.sign = sign
        this.password = password
        return result
    }

    override fun safetyNumber(email: String): Result<String> {
        safetyEmail = email
        return Result.success("42 42 42")
    }
}

class EncryptionControllerTest {

    private val controller = EncryptionController(MockPgpBridge())

    @Test
    fun explicitModeEncryptsTextThroughBridge() {
        val armored = controller.encrypt("Hello world").getOrThrow()
        assertTrue(armored.startsWith("-----BEGIN PGP MESSAGE-----"))
    }

    @Test
    fun blankTextIsRejected() {
        assertTrue(controller.encrypt("").isFailure)
    }

    @Test
    fun autoAppendPreservesOriginalTextAndAddsBlankLine() {
        val armored = controller.encrypt("Hello world").getOrThrow()
        val combined = controller.buildAutoAppendText("Hello world  ", armored)

        assertTrue(combined.startsWith("Hello world\n\n"))
        assertTrue(combined.contains(armored.trim()))
        assertTrue(combined.endsWith("\n"))
    }

    @Test
    fun failuresArePropagatedAndDescribed() {
        val failure = controller.encrypt(" ")
        assertTrue(failure.isFailure)
        assertTrue(controller.describeFailure(failure.exceptionOrNull()!!).contains("Nothing to encrypt"))
    }

    @Test
    fun passwordRecipientsAndSignFlagArePassedToBridge() {
        val bridge = RecordingBridge()
        val subject = EncryptionController(
            bridge,
            recipientsCsv = "alice@example.com,bob@example.com",
            sign = true,
            passwordProvider = { "saved-key-password" }
        )

        assertEquals("ARMOR", subject.encrypt("hello").getOrThrow())
        assertEquals("hello", bridge.plaintext)
        assertEquals("alice@example.com,bob@example.com", bridge.recipientsCsv)
        assertEquals(true, bridge.sign)
        assertEquals("saved-key-password", bridge.password)
    }

    @Test
    fun explicitPasswordOverrideIsForwarded() {
        val bridge = RecordingBridge()
        val subject = EncryptionController(bridge)

        subject.encrypt("hello", "one-time-password")

        assertEquals("one-time-password", bridge.password)
    }

    @Test
    fun safetyNumberIsDelegatedToBridge() {
        val bridge = RecordingBridge()
        val subject = EncryptionController(bridge)

        assertEquals("42 42 42", subject.safetyNumber("alice@example.com").getOrThrow())
        assertEquals("alice@example.com", bridge.safetyEmail)
    }
}
