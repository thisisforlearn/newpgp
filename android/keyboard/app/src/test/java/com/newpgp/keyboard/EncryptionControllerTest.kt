// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Vaibhav - https://github.com/thisisforlearn
package com.newpgp.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

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
}
