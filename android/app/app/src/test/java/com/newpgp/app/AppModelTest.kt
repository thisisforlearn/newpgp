// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Vaibhav - https://github.com/thisisforlearn
package com.newpgp.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Drives every user flow on the JVM; no Android runtime is involved. */
class AppModelTest {

    private fun demoModel(): AppModel = AppModel(MockPgpBridge(), Dispatchers.Unconfined)

    @Test
    fun keyCreationUpdatesState() = runTest {
        val model = demoModel()
        assertNull(model.state.value.identity)

        val result = model.createIdentity("me@example.test", "Me")

        assertTrue(result.isSuccess)
        val state = model.state.value
        assertEquals("Me", state.identity?.displayName)
        assertEquals(AppModel.KEY_READY, state.statusMessage)
        assertFalse(state.statusIsError)
        assertFalse(state.isBusy)
    }

    @Test
    fun failedKeyCreationShowsVisibleError() = runTest {
        val model = demoModel()

        val result = model.createIdentity(" ", " ")

        assertTrue(result.isFailure)
        assertNull(model.state.value.identity)
        assertTrue(model.state.value.statusIsError)
        assertNotNull(model.state.value.statusMessage)
    }

    @Test
    fun publicKeyIsAvailableAfterCreation() = runTest {
        val model = demoModel()
        model.createIdentity("me@example.test", "Me")

        assertTrue(model.refreshPublicKey().isSuccess)

        assertTrue(model.state.value.publicKeyArmored!!.contains("BEGIN PGP PUBLIC KEY BLOCK"))
    }

    @Test
    fun composeBothKeepsPlaintextWhilePgpOnlyHidesIt() = runTest {
        val model = demoModel()
        model.createIdentity("me@example.test", "Me")
        val recipients = listOf("friend@example.test")
        val plaintext = "Meet at noon"

        val both = model.prepareShare(plaintext, recipients, pgpOnly = false).getOrThrow()
        assertTrue(both.contains(plaintext))
        assertEquals(AppModel.SEND_BOTH_READY, model.state.value.statusMessage)
        assertEquals(both, model.state.value.lastComposed)

        val pgpOnly = model.prepareShare(plaintext, recipients, pgpOnly = true).getOrThrow()
        assertFalse(pgpOnly.contains(plaintext))
        assertTrue(pgpOnly.contains(MockPgpBridge.MOCK_MARKER))
        assertEquals(AppModel.PGP_ONLY_READY, model.state.value.statusMessage)
    }

    @Test
    fun decryptUpdatesStateWithSignatureStatus() = runTest {
        val model = demoModel()
        model.createIdentity("me@example.test", "Me")
        val armor = model.prepareShare("hidden words", listOf("friend@example.test"), true).getOrThrow()

        val result = model.decrypt(armor)

        assertTrue(result.isSuccess)
        assertEquals("hidden words", result.getOrThrow().plaintext)
        assertEquals(SignatureStatus.SIGNED_UNVERIFIED, model.state.value.lastDecryption?.signatureStatus)
        assertEquals(AppModel.DECRYPTED, model.state.value.statusMessage)
    }

    @Test
    fun safetyNumberVerificationMarksContactVerified() = runTest {
        val model = demoModel()
        assertTrue(model.refreshContacts().isSuccess)
        val contact = model.state.value.contacts.first()
        val number = model.safetyNumber(contact).getOrThrow()

        val verified = model.verifySafetyNumber(contact, number).getOrThrow()

        assertTrue(verified.matches)
        assertTrue(model.state.value.contacts.first { it.email == contact.email }.verified)
        assertEquals(AppModel.SAFETY_MATCH, model.state.value.statusMessage)
        assertFalse(model.state.value.statusIsError)

        model.verifySafetyNumber(contact, "ZZZZZ ZZZZZ")
        assertFalse(model.state.value.contacts.first { it.email == contact.email }.verified)
        assertEquals(AppModel.SAFETY_MISMATCH, model.state.value.statusMessage)
        assertTrue(model.state.value.statusIsError)
    }

    @Test
    fun nativeBridgeFailsClosed() = runTest {
        val model = AppModel(NativePgpBridge(), Dispatchers.Unconfined)

        val result = model.createIdentity("me@example.test", "Me")

        assertTrue(result.isFailure)
        assertEquals(NativePgpBridge.NOT_CONNECTED_MESSAGE, result.exceptionOrNull()?.message)
        assertNull(model.state.value.identity)
        assertTrue(model.state.value.statusIsError)
    }
}
