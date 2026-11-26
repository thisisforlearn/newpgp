// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Vaibhav - https://github.com/thisisforlearn
package com.newpgp.keyboard.ui

import com.newpgp.keyboard.KeyLayouts
import com.newpgp.keyboard.KeyType
import com.newpgp.keyboard.KeyboardLayer
import com.newpgp.keyboard.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure JVM checks for the toolbar/key mapping the service agent consumes. */
class KeyLayoutMappingTest {

    @Test
    fun toolbarHasBrandAllRequestedActionsAndShield() {
        val toolbar = KeyLayouts.toolbarRow()
        assertTrue(toolbar.isToolbar)
        val types = toolbar.keys.map { it.type }
        assertEquals(
            listOf(
                KeyType.BRAND, KeyType.CLIPBOARD, KeyType.AI_TONE, KeyType.MIC,
                KeyType.EMOJI, KeyType.GIF, KeyType.MORE, KeyType.SHIELD
            ),
            types
        )
        assertEquals(R.drawable.ic_newpgp_brand, toolbar.keys[0].iconRes)
        assertEquals(R.drawable.ic_sparkle, toolbar.keys[2].iconRes)
        assertNotNull(toolbar.keys.last().tooltipRes)
        assertTrue(toolbar.keys.all { it.weight > 0f })

        // Brand is decorative and must not carry a dispatchable event.
        assertTrue(toolbar.keys.first().descriptionRes != null)
        assertFalse(toolbar.keys.first().type == KeyType.ENCRYPT)
    }

    @Test
    fun aiToneAndShieldAreDistinctServiceEvents() {
        val toolbar = KeyLayouts.toolbarRow().keys
        val ai = toolbar.first { it.type == KeyType.AI_TONE }
        val shield = toolbar.first { it.type == KeyType.SHIELD }
        assertEquals(R.drawable.ic_sparkle, ai.iconRes)
        assertEquals(R.string.toolbar_ai_tone, ai.descriptionRes)
        assertEquals(R.drawable.ic_shield_lock, shield.iconRes)
        assertEquals(R.string.toolbar_shield, shield.descriptionRes)
        assertEquals(R.string.toolbar_shield_tooltip, shield.tooltipRes)
        assertTrue(ai.type != shield.type)
    }

    @Test
    fun layersKeepQwertyNumbersSymbolsAndBottomActions() {
        val letters = KeyLayouts.rows(KeyboardLayer.LETTERS, shiftActive = false)
        assertEquals(4, letters.size)
        assertEquals("qwertyuiop", letters[0].keys.map { it.text }.joinToString(""))
        assertEquals("asdfghjkl", letters[1].keys.filter { it.type == KeyType.CHARACTER }
            .map { it.text }.joinToString(""))

        val shifted = KeyLayouts.rows(KeyboardLayer.LETTERS, shiftActive = true)
        assertEquals(R.drawable.ic_shift_locked, shifted[2].keys.first().iconRes)
        assertEquals(R.drawable.ic_shift, letters[2].keys.first().iconRes)

        val bottom = letters.last().keys.map { it.type }
        assertTrue(bottom.containsAll(listOf(KeyType.SPACE, KeyType.ENTER, KeyType.ENCRYPT, KeyType.EMOJI)))
        assertTrue(KeyLayouts.rows(KeyboardLayer.NUMBERS, false)[0].keys
            .all { it.text?.length == 1 && it.text!![0].isDigit() })
        assertTrue(KeyLayouts.rows(KeyboardLayer.SYMBOLS, false).size == 4)
        assertEquals(5, KeyLayouts.rows(KeyboardLayer.EMOJI, false).size)
    }
}
