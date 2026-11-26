// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Vaibhav - https://github.com/thisisforlearn
package com.newpgp.keyboard

import com.newpgp.keyboard.autocorrect.AutocorrectEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** JVM proof of the shield contract: armor only on success, nothing on failure. */
class ShieldEncryptorTest {

    private class FakeBridge(
        private val outcome: Result<String> = Result.success("ARMOR"),
        private val onEncrypt: () -> Unit = {}
    ) : PgpBridge {
        override val displayName: String = "fake"
        var lastPassword: String? = null
        var lastPlaintext: String? = null

        override fun encrypt(plaintext: String): Result<String> =
            encrypt(plaintext, "", false, "")

        override fun encrypt(
            plaintext: String,
            recipientsCsv: String,
            sign: Boolean,
            password: String
        ): Result<String> {
            lastPlaintext = plaintext
            lastPassword = password
            onEncrypt()
            return outcome
        }
    }

    private fun subject(bridge: PgpBridge): Pair<ShieldEncryptor, ImeTextController> {
        val text = ImeTextController(AutocorrectEngine(), autocorrectEnabled = { false })
        return ShieldEncryptor(text, EncryptionController(bridge)) to text
    }

    private fun ImeTextController.type(ic: FakeTextEditConnection, word: String) {
        word.forEach { typeCharacter(ic, it.toString(), false) }
    }

    @Test
    fun shieldCommitsOnlyArmorAndLeavesNoComposingPlaintext() {
        val ic = FakeTextEditConnection()
        ic.setText("Hello ")
        val (shield, text) = subject(FakeBridge())
        text.type(ic, "secret")

        assertTrue(shield.run(ic, "secret", "").isSuccess)

        assertEquals("Hello ARMOR", ic.text())
        assertFalse(ic.text().contains("secret"))
        assertEquals("", text.composingText())
        assertEquals(-1, ic.composingStart)
        assertEquals(0, ic.batchDepth)
    }

    @Test
    fun selectedTextIsReplacedWithArmor() {
        val ic = FakeTextEditConnection()
        ic.setText("Hello secret world")
        ic.setSelection(6, 12)
        val (shield, _) = subject(FakeBridge())

        assertTrue(shield.run(ic, "secret", "").isSuccess)

        assertEquals("Hello ARMOR world", ic.text())
        assertFalse(ic.text().contains("secret"))
    }

    @Test
    fun failureCommitsNothingAndKeepsTheTypedText() {
        val ic = FakeTextEditConnection()
        // Model the real bridge: a genuinely absent engine fails with its dedicated
        // non-sensitive copy, not with a raw "engine missing" implementation detail.
        val (shield, text) = subject(
            FakeBridge(
                outcome = Result.failure(
                    UnsupportedOperationException(NativePgpBridge.MISSING_ENGINE_MESSAGE)
                )
            )
        )
        text.type(ic, "secret")

        val result = shield.run(ic, "secret", "")

        assertTrue(result.isFailure)
        // Nothing is committed: no armor, no plaintext loss, no leaked batch.
        assertEquals("secret", ic.text())
        assertEquals("secret", text.composingText())
        assertEquals(0, ic.batchDepth)
        // Plain-language guidance that both explains the cause and says the text
        // is still there. Use substring/regex, not a brittle exact string.
        val message = result.exceptionOrNull()?.message.orEmpty()
        assertTrue(
            "expected missing-engine guidance but was: $message",
            Regex("engine.*missing", RegexOption.IGNORE_CASE).containsMatchIn(message)
        )
        assertTrue(
            "expected reassurance that nothing was inserted but was: $message",
            message.contains("nothing was inserted", ignoreCase = true)
        )
    }

    @Test
    fun genericEncryptionFailureUsesFriendlyCopyAndNeverLeaksRawError() {
        val ic = FakeTextEditConnection()
        ic.setText("keep me")
        val (shield, _) = subject(
            FakeBridge(outcome = Result.failure(SecurityException("opaque native error 0x1337")))
        )

        val result = shield.run(ic, "keep me", "")

        assertTrue(result.isFailure)
        assertEquals("keep me", ic.text())
        val message = result.exceptionOrNull()?.message.orEmpty()
        assertTrue(
            "expected generic failure copy but was: $message",
            message.contains("Encryption failed", ignoreCase = true)
        )
        assertTrue(
            "expected reassurance that nothing was inserted but was: $message",
            message.contains("nothing was inserted", ignoreCase = true)
        )
        assertFalse(
            "raw bridge error text must never reach the user: $message",
            message.contains("opaque native error")
        )
    }

    @Test
    fun passwordIsPassedThroughToTheBridge() {
        val bridge = FakeBridge()
        val ic = FakeTextEditConnection()
        val (shield, text) = subject(bridge)
        text.type(ic, "secret")

        shield.run(ic, "secret", "correct horse battery staple")

        assertEquals("secret", bridge.lastPlaintext)
        assertEquals("correct horse battery staple", bridge.lastPassword)
    }

    @Test
    fun explicitWholeFieldActionReplacesTheEntireField() {
        val ic = FakeTextEditConnection()
        ic.setText("a long plaintext message", 6)
        val (shield, text) = subject(FakeBridge())
        val snapshot = text.wholeFieldSnapshot(ic)

        assertTrue(shield.run(ic, snapshot.text, "", snapshot.fieldLength).isSuccess)

        assertEquals("ARMOR", ic.text())
    }

    @Test
    fun blankArmorIsNeverCommitted() {
        val ic = FakeTextEditConnection()
        ic.setText("keep me")
        val (shield, _) = subject(FakeBridge(outcome = Result.success("   ")))

        assertTrue(shield.run(ic, "keep me", "").isFailure)

        assertEquals("keep me", ic.text())
    }

    @Test
    fun staleInputSessionCommitsNothing() {
        val ic = FakeTextEditConnection()
        ic.setText("secret")
        lateinit var text: ImeTextController
        val bridge = FakeBridge(onEncrypt = { text.startInput() })
        val subject = ShieldEncryptor(
            ImeTextController(AutocorrectEngine(), autocorrectEnabled = { false }).also { text = it },
            EncryptionController(bridge)
        )

        val result = subject.run(ic, "secret", "")

        assertTrue(result.isFailure)
        assertEquals("secret", ic.text())
    }
}
