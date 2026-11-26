// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Vaibhav - https://github.com/thisisforlearn
package com.newpgp.keyboard.ui

import com.newpgp.keyboard.KeyGeometry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Benchmark assumptions: a 360dp-wide IME view with 4dp horizontal padding, 8dp key
 * gaps, a 48dp toolbar and four 56dp rows (304dp total). 320dp-wide views are checked
 * as the small-phone lower bound; no per-keystroke geometry work happens at runtime.
 */
class KeyGeometryTest {

    @Test
    fun rowWidthsFillUsableSpaceAndStayNonNegative() {
        for (viewWidth in listOf(320f, 360f, 411f)) {
            val usable = viewWidth - 8f
            val weights = listOf(1.4f, 1f, 4.2f, 1f, 1.2f, 1f, 1.5f)
            val gap = 8f
            val placements = KeyGeometry.placeRow(weights, 4f, usable, gap)
            assertEquals(weights.size, placements.size)
            assertTrue(placements.all { it.width > 0f })
            val last = placements.last()
            assertEquals(viewWidth - 4f, last.x + last.width, 0.001f)
            for (index in 1 until placements.size) {
                val expected = placements[index - 1].x + placements[index - 1].width + gap
                assertEquals(expected, placements[index].x, 0.001f)
            }
        }
    }

    @Test
    fun toolbarAndFourRowsFitTheKeyboardHeightBudget() {
        val heights = KeyGeometry.rowHeights(5, 304f, 8f, 48f)
        assertEquals(5, heights.size)
        assertEquals(48f, heights[0], 0.001f)
        assertTrue(heights.drop(1).all { it == 56f })
        val total = heights.sum() + 8f * 4
        assertEquals(304f, total, 0.001f)
    }

    @Test
    fun degenerateInputsDoNotProduceNegativeSizes() {
        assertTrue(KeyGeometry.placeRow(emptyList(), 4f, 320f, 8f).isEmpty())
        assertTrue(KeyGeometry.rowHeights(0, 304f, 8f, 48f).isEmpty())
        assertTrue(KeyGeometry.rowHeights(5, 8f, 8f, 48f).all { it == 0f })
    }
}
