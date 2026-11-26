// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Vaibhav - https://github.com/thisisforlearn
package com.newpgp.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NativePgpBridgeTest {

    @Test
    fun missingLibraryReportsNoRealCryptoAndFriendlyCopy() {
        val bridge = NativePgpBridge(nativeAvailable = false)

        assertFalse(bridge.isRealCrypto)
        assertTrue(bridge.displayName.contains("engine missing"))
        assertEquals(
            NativePgpBridge.MISSING_ENGINE_MESSAGE,
            bridge.encrypt("secret", "", false, "").exceptionOrNull()?.message
        )
        assertEquals(
            NativePgpBridge.MISSING_ENGINE_MESSAGE,
            bridge.generateIdentity("Name", "a@example.com", "pw").exceptionOrNull()?.message
        )
        assertEquals(
            NativePgpBridge.MISSING_ENGINE_MESSAGE,
            bridge.safetyNumber("").exceptionOrNull()?.message
        )
    }

    @Test
    fun initializeWithoutLibraryIsSafe() {
        val bridge = NativePgpBridge(nativeAvailable = false)
        bridge.initialize(System.getProperty("java.io.tmpdir") ?: ".")
        assertFalse(bridge.isRealCrypto)
    }

    @Test
    fun missingNativeSymbolIsConvertedToFriendlyFailure() {
        // JVM unit tests have no newpgp_mobile .so, so the external call throws
        // UnsatisfiedLinkError. It must never escape into the IME.
        val bridge = NativePgpBridge(nativeAvailable = true)
        assertTrue(bridge.isRealCrypto)

        val result = bridge.encrypt("secret", "", false, "")

        assertTrue(result.isFailure)
        assertEquals(NativePgpBridge.MISSING_ENGINE_MESSAGE, result.exceptionOrNull()?.message)
        assertFalse(bridge.isRealCrypto)
    }

    @Test
    fun errorMessagesNeverContainPlaintextOrPassword() {
        val result = NativePgpBridge(nativeAvailable = false)
            .encrypt("super secret text", "", false, "hunter2")

        val message = result.exceptionOrNull()?.message.orEmpty()
        assertFalse(message.contains("secret"))
        assertFalse(message.contains("hunter2"))
    }
}
