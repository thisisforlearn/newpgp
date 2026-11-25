// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Vaibhav - https://github.com/thisisforlearn
package com.newpgp.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests the exact path used by ShareActivity: shared text goes through
 * AppModel.prepareShare, the same call the Write tab uses.
 */
class ShareFlowTest {

    private fun demoModel(): AppModel = AppModel(MockPgpBridge(), Dispatchers.Unconfined)

    @Test
    fun encryptAndCopyPayloadKeepsPlaintextByDefault() = runTest {
        val model = demoModel()
        model.createIdentity("sender@example.test", "Sender")
        val sharedText = "Text received from another app"

        val payload = model.prepareShare(
            text = sharedText,
            recipients = listOf("friend@example.test"),
            pgpOnly = false,
        ).getOrThrow()

        assertTrue(payload.startsWith(sharedText))
        assertTrue(payload.contains(MockPgpBridge.MOCK_MARKER))
        assertEquals(payload, model.state.value.lastComposed)
    }

    @Test
    fun encryptAndSharePayloadCanHidePlaintext() = runTest {
        val model = demoModel()
        model.createIdentity("sender@example.test", "Sender")
        val sharedText = "Text received from another app"

        val payload = model.prepareShare(
            text = sharedText,
            recipients = listOf("friend@example.test"),
            pgpOnly = true,
        ).getOrThrow()

        assertFalse(payload.contains(sharedText))
        assertTrue(payload.contains("-----BEGIN PGP MESSAGE-----"))
    }

    @Test
    fun shareWithoutRecipientFailsVisibly() = runTest {
        val model = demoModel()

        val result = model.prepareShare("shared text", emptyList(), pgpOnly = false)

        assertTrue(result.isFailure)
        assertNullPayload(model)
        assertTrue(model.state.value.statusIsError)
    }

    @Test
    fun shareWithNativeBridgeCannotProduceCiphertext() = runTest {
        val model = AppModel(NativePgpBridge(), Dispatchers.Unconfined)

        val result = model.prepareShare("shared text", listOf("friend@example.test"), pgpOnly = false)

        assertTrue(result.isFailure)
        assertEquals(NativePgpBridge.NOT_CONNECTED_MESSAGE, result.exceptionOrNull()?.message)
        assertNullPayload(model)
    }

    private fun assertNullPayload(model: AppModel) {
        assertTrue(model.state.value.lastComposed == null)
    }
}
