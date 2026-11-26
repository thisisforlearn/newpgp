// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Vaibhav - https://github.com/thisisforlearn
package com.newpgp.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure JVM tests for the first-run wizard. They cover the step-advance rules,
 * the honest failure path when the native bridge is not connected, and the
 * invariant that no fake key or ciphertext can ever appear in wizard state.
 */
class WizardStateMachineTest {

    private fun fakeIdentity(): Identity = Identity(
        displayName = "Pretender",
        email = "pretender@example.test",
        fingerprint = "0000 0000 0000 0000",
        publicKeyArmored = "-----BEGIN PGP PUBLIC KEY BLOCK-----",
    )

    @Test
    fun startsOnWelcomeWithNoKeyMaterial() {
        val wizard = WizardStateMachine(isRealCrypto = false)
        val state = wizard.state.value

        assertEquals(WizardStep.WELCOME, state.step)
        assertNull(state.fingerprint)
        assertNull(state.publicKeyArmored)
        assertNull(state.blockedReason)
        assertFalse(state.finished)
    }

    @Test
    fun getStartedAdvancesOnlyToMakeKeyAndNeverSkips() {
        val wizard = WizardStateMachine(isRealCrypto = true)

        wizard.getStarted()
        assertEquals(WizardStep.MAKE_KEY, wizard.state.value.step)

        wizard.getStarted()
        assertEquals(WizardStep.MAKE_KEY, wizard.state.value.step)
        assertNull(wizard.state.value.fingerprint)
    }

    @Test
    fun successCallbackCannotJumpFromWelcomeToReady() {
        val wizard = WizardStateMachine(isRealCrypto = true)

        wizard.onKeyCreated(fakeIdentity())

        assertEquals(WizardStep.WELCOME, wizard.state.value.step)
        assertNull(wizard.state.value.fingerprint)
        wizard.finish()
        assertFalse(wizard.state.value.finished)
    }

    @Test
    fun nativeBridgeBlocksKeyCreationWithHonestReason() {
        val wizard = WizardStateMachine(isRealCrypto = false)
        wizard.getStarted()
        wizard.setDisplayName("Ada")
        wizard.setEmail("ada@example.test")

        val action = wizard.requestKeyCreation()

        assertTrue(action is WizardKeyAction.Blocked)
        val state = wizard.state.value
        assertEquals(WizardStep.MAKE_KEY, state.step)
        assertEquals(WizardBlockedReason.ENGINE_NOT_CONNECTED, state.blockedReason)
        assertNull(state.fingerprint)
        assertNull(state.publicKeyArmored)
        assertFalse(state.finished)

        wizard.dismissBlocked()
        assertNull(wizard.state.value.blockedReason)
    }

    @Test
    fun noFakeSuccessWhenCallerSuppliesIdentityToStub() {
        val wizard = WizardStateMachine(isRealCrypto = false)
        wizard.getStarted()

        // Even a caller ignoring the gate cannot unlock step 3 with the stub.
        wizard.onKeyCreated(fakeIdentity())
        wizard.finish()

        val state = wizard.state.value
        assertEquals(WizardStep.MAKE_KEY, state.step)
        assertEquals(WizardBlockedReason.ENGINE_NOT_CONNECTED, state.blockedReason)
        assertNull(state.fingerprint)
        assertNull(state.publicKeyArmored)
        assertFalse(state.finished)
    }

    @Test
    fun realBridgeStillRequiresBothFields() {
        val wizard = WizardStateMachine(isRealCrypto = true)
        wizard.getStarted()

        assertTrue(wizard.requestKeyCreation() is WizardKeyAction.Blocked)
        assertEquals(WizardBlockedReason.MISSING_FIELDS, wizard.state.value.blockedReason)

        wizard.setDisplayName("  Ada  ")
        assertTrue(wizard.requestKeyCreation() is WizardKeyAction.Blocked)

        wizard.setEmail("  ada@example.test  ")
        val action = wizard.requestKeyCreation()
        assertTrue(action is WizardKeyAction.Create)
        action as WizardKeyAction.Create
        assertEquals("Ada", action.displayName)
        assertEquals("ada@example.test", action.email)
        assertNull(wizard.state.value.blockedReason)
    }

    @Test
    fun realBridgeSuccessOpensStepThreeAndAllowsFinish() {
        val wizard = WizardStateMachine(isRealCrypto = true)
        wizard.getStarted()
        wizard.setDisplayName("Ada")
        wizard.setEmail("ada@example.test")
        assertTrue(wizard.requestKeyCreation() is WizardKeyAction.Create)

        val identity = fakeIdentity()
        wizard.onKeyCreated(identity)

        val ready = wizard.state.value
        assertEquals(WizardStep.READY, ready.step)
        assertEquals(identity.fingerprint, ready.fingerprint)
        assertEquals(identity.publicKeyArmored, ready.publicKeyArmored)
        assertNull(ready.blockedReason)

        wizard.finish()
        assertTrue(wizard.state.value.finished)
    }

    @Test
    fun failedRealBridgeCreationDoesNotAdvance() {
        val wizard = WizardStateMachine(isRealCrypto = true)
        wizard.getStarted()
        wizard.setDisplayName("Ada")
        wizard.setEmail("ada@example.test")

        wizard.onKeyCreationFailed()

        assertEquals(WizardStep.MAKE_KEY, wizard.state.value.step)
        assertEquals(WizardBlockedReason.KEY_CREATION_FAILED, wizard.state.value.blockedReason)
        assertNull(wizard.state.value.fingerprint)
    }

    @Test
    fun stubBridgeCannotProduceIdentityOrCiphertext() = runTest {
        val wizard = WizardStateMachine(isRealCrypto = false)
        wizard.getStarted()
        wizard.setDisplayName("Ada")
        wizard.setEmail("ada@example.test")
        assertTrue(wizard.requestKeyCreation() is WizardKeyAction.Blocked)

        // The wizard never reaches a bridge, and the bridge itself fails closed.
        val model = AppModel(NativePgpBridge(), Dispatchers.Unconfined)
        assertTrue(model.createIdentity("ada@example.test", "Ada").isFailure)
        assertTrue(
            model.prepareShare("secret", listOf("friend@example.test"), pgpOnly = true).isFailure,
        )

        assertNull(model.state.value.identity)
        assertNull(model.state.value.publicKeyArmored)
        assertNull(model.state.value.lastComposed)
        assertNull(wizard.state.value.fingerprint)
        assertNull(wizard.state.value.publicKeyArmored)
    }
}
