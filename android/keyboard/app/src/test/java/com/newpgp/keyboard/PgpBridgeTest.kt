// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Vaibhav - https://github.com/thisisforlearn
package com.newpgp.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PgpBridgeTest {

    @Test
    fun mockBridgeProducesDeterministicArmor() {
        val bridge = MockPgpBridge()
        val first = bridge.encrypt("hello").getOrThrow()
        val second = bridge.encrypt("hello").getOrThrow()

        assertEquals(first, second)
        assertTrue(first.startsWith("-----BEGIN PGP MESSAGE-----"))
        assertTrue(first.contains("68656C6C6F")) // ASCII hex of "hello"
        assertTrue(first.trimEnd().endsWith("-----END PGP MESSAGE-----"))
    }

    @Test
    fun mockBridgeRejectsBlankInput() {
        assertTrue(MockPgpBridge().encrypt("   ").isFailure)
    }

    @Test
    fun missingPrivateEngineFailsClosedWithReinstallCopy() {
        val bridge = NativePgpBridge(nativeAvailable = false)

        assertFalse(bridge.isRealCrypto)
        val failure = bridge.encrypt("top secret")
        assertTrue(failure.isFailure)
        assertTrue(failure.exceptionOrNull() is UnsupportedOperationException)
        assertEquals(NativePgpBridge.MISSING_ENGINE_MESSAGE, failure.exceptionOrNull()?.message)
        assertTrue(failure.exceptionOrNull()?.message!!.contains("reinstall the APK"))
    }

    @Test
    fun bridgeClaimsRealCryptoOnlyWhenLibraryLoaded() {
        assertTrue(NativePgpBridge(nativeAvailable = true).isRealCrypto)
        assertFalse(NativePgpBridge(nativeAvailable = false).isRealCrypto)
        assertFalse(MockPgpBridge().isRealCrypto)
    }

    @Test
    fun defaultProviderIsTheNativeBridge() {
        // JVM tests have no .so; the provider still hands out the honest native bridge.
        assertTrue(PgpBridgeProvider.current is NativePgpBridge)
    }
}
