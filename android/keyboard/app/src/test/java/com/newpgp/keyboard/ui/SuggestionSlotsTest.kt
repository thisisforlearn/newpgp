// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Vaibhav - https://github.com/thisisforlearn
package com.newpgp.keyboard.ui

import com.newpgp.keyboard.SuggestionSlots
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** The strip keeps exactly three slots and reuses one holder for every update. */
class SuggestionSlotsTest {

    @Test
    fun padsMissingSlotsAndDropsExtraSuggestions() {
        val slots = SuggestionSlots().update(listOf("left", "typed", "right", "ignored"))
        assertEquals("left", slots.left)
        assertEquals("typed", slots.centre)
        assertEquals("right", slots.right)
        assertEquals("", slots.at(3))
        assertTrue(slots.hasSuggestions)

        slots.update(listOf("only"))
        assertEquals("only", slots.left)
        assertEquals("", slots.centre)
        assertEquals("", slots.right)

        slots.update(emptyList())
        assertFalse(slots.hasSuggestions)
        assertEquals("", slots.at(1))
    }

    @Test
    fun updateReturnsSameInstanceForAllocationFreeTyping() {
        val slots = SuggestionSlots()
        assertSame(slots, slots.update(listOf("a", "b", "c")))
        assertSame(slots, slots.update(listOf("d")))
    }

    @Test
    fun centreSlotCarriesTheTypedWord() {
        val slots = SuggestionSlots().update(listOf("teh", "the", "tech"))
        assertEquals("the", slots.at(1))
    }
}
