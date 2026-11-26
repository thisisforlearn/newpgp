// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Vaibhav - https://github.com/thisisforlearn
package com.newpgp.keyboard

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Proves the injectable loader selects the real bridge only when load succeeds. */
class PgpBridgeProviderTest {

    private val originalLoader = PgpBridgeProvider.libraryLoader
    private val originalCurrent = PgpBridgeProvider.current

    @After
    fun restore() {
        PgpBridgeProvider.libraryLoader = originalLoader
        PgpBridgeProvider.current = originalCurrent
    }

    @Test
    fun libraryPresentSelectsRealBridge() {
        var loadCalls = 0
        PgpBridgeProvider.libraryLoader = { loadCalls++ }

        val bridge = PgpBridgeProvider.createBridge()

        assertTrue(bridge is NativePgpBridge)
        assertTrue(bridge.isRealCrypto)
        assertEquals(1, loadCalls)
    }

    @Test
    fun libraryMissingSelectsFailingNativeBridge() {
        PgpBridgeProvider.libraryLoader = { throw UnsatisfiedLinkError("no newpgp_mobile") }

        val bridge = PgpBridgeProvider.createBridge()

        assertTrue(bridge is NativePgpBridge)
        assertFalse(bridge.isRealCrypto)
        assertEquals(
            NativePgpBridge.MISSING_ENGINE_MESSAGE,
            bridge.encrypt("secret").exceptionOrNull()?.message
        )
    }

    @Test
    fun reloadReplacesTheCurrentBridge() {
        PgpBridgeProvider.libraryLoader = { throw UnsatisfiedLinkError("missing") }
        PgpBridgeProvider.reload()

        assertFalse(PgpBridgeProvider.current.isRealCrypto)

        PgpBridgeProvider.libraryLoader = {}
        PgpBridgeProvider.reload()

        assertTrue(PgpBridgeProvider.current.isRealCrypto)
    }
}
