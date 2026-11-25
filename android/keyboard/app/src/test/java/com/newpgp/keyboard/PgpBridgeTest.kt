// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Vaibhav - https://github.com/thisisforlearn
package com.newpgp.keyboard

import org.junit.Assert.assertEquals
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
    fun nativeStubFailsClosedInsteadOfPretendingToEncrypt() {
        val result = NativePgpBridge().encrypt("top secret")
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is UnsupportedOperationException)
        assertEquals(
            NativePgpBridge.NOT_CONNECTED_MESSAGE,
            result.exceptionOrNull()?.message
        )
    }

    @Test
    fun defaultProviderIsTheHonestNativeStub() {
        assertTrue(PgpBridgeProvider.current is NativePgpBridge)
    }
}
