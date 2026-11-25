// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Vaibhav - https://github.com/thisisforlearn
package com.newpgp.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure-JVM tests for the deterministic, clearly-labelled demo bridge. */
class MockPgpBridgeTest {

    private val bridge = MockPgpBridge()

    @Test
    fun createIdentityProducesClearlyFakeKey() {
        val identity = bridge.createIdentity("Ada@Example.test", "Ada").getOrThrow()

        assertEquals("Ada", identity.displayName)
        assertEquals("ada@example.test", identity.email)
        assertEquals(MockPgpBridge.fingerprintFor("ada@example.test"), identity.fingerprint)
        assertTrue(identity.publicKeyArmored.contains("BEGIN PGP PUBLIC KEY BLOCK"))
        assertTrue(identity.publicKeyArmored.contains(MockPgpBridge.MOCK_MARKER))
        assertFalse(bridge.isRealCrypto)
    }

    @Test
    fun publicKeyFailsBeforeIdentityExists() {
        assertTrue(bridge.publicKey().isFailure)
    }

    @Test
    fun invalidIdentityInputFails() {
        assertTrue(bridge.createIdentity("not-an-email", "Ada").isFailure)
        assertTrue(bridge.createIdentity("ada@example.test", " ").isFailure)
    }

    @Test
    fun composeSendBothIncludesPlaintextAndArmor() {
        bridge.createIdentity("sender@example.test", "Sender")
        val output = bridge.compose("A private note", listOf("friend@example.test"), false).getOrThrow()

        assertTrue(output.contains("A private note"))
        assertTrue(output.contains("-----BEGIN PGP MESSAGE-----"))
        assertTrue(output.contains(MockPgpBridge.MOCK_MARKER))
    }

    @Test
    fun composePgpOnlyHidesPlaintext() {
        bridge.createIdentity("sender@example.test", "Sender")
        val plaintext = "A private note"
        val output = bridge.compose(plaintext, listOf("friend@example.test"), true).getOrThrow()

        assertFalse(output.contains(plaintext))
        assertTrue(output.contains("-----BEGIN PGP MESSAGE-----"))
        assertTrue(output.contains(MockPgpBridge.MOCK_MARKER))
    }

    @Test
    fun decryptRoundTripReportsUnverifiedSignature() {
        bridge.createIdentity("sender@example.test", "Sender")
        val armor = bridge.encrypt("hello world", listOf("friend@example.test")).getOrThrow()
        val decoded = bridge.decrypt(armor).getOrThrow()

        assertEquals("hello world", decoded.plaintext)
        assertEquals(SignatureStatus.SIGNED_UNVERIFIED, decoded.signatureStatus)
        assertEquals(MockPgpBridge.fingerprintFor("sender@example.test"), decoded.signerFingerprint)
    }

    @Test
    fun decryptRejectsForeignArmorWithoutProducingPlaintext() {
        val foreign = "-----BEGIN PGP MESSAGE-----\n\nabcd\n-----END PGP MESSAGE-----\n"
        assertTrue(bridge.decrypt(foreign).isFailure)
    }

    @Test
    fun encryptRequiresTextAndRecipient() {
        assertTrue(bridge.encrypt(" ", listOf("friend@example.test")).isFailure)
        assertTrue(bridge.encrypt("hello", emptyList()).isFailure)
    }

    @Test
    fun safetyNumberVerificationMatchesAndMismatches() {
        val contact = Contact("Bob", "bob@example.test", MockPgpBridge.fingerprintFor("bob@example.test"))
        val number = bridge.safetyNumber(contact).getOrThrow()

        val match = bridge.verifySafetyNumber(contact, number.lowercase()).getOrThrow()
        assertTrue(match.matches)
        assertTrue(match.contact.verified)
        assertEquals(number, match.canonicalNumber)

        val mismatch = bridge.verifySafetyNumber(contact, "AAAAA BBBBB").getOrThrow()
        assertFalse(mismatch.matches)
        assertFalse(mismatch.contact.verified)
        assertTrue(bridge.verifySafetyNumber(contact, " ").isFailure)
    }

    @Test
    fun discoverKeyAddsContactOnce() {
        assertEquals(2, bridge.contacts().getOrThrow().size)

        val found = bridge.discoverKey("NEW@example.test").getOrThrow()
        assertEquals("new@example.test", found.email)
        assertEquals(3, bridge.contacts().getOrThrow().size)

        bridge.discoverKey("new@example.test").getOrThrow()
        assertEquals(3, bridge.contacts().getOrThrow().size)
        assertTrue(bridge.discoverKey("bad-address").isFailure)
    }
}
