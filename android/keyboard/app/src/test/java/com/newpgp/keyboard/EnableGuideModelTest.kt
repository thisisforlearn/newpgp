// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Vaibhav - https://github.com/thisisforlearn
package com.newpgp.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EnableGuideModelTest {

    @Test
    fun guideHasFourConsecutiveSteps() {
        assertEquals(4, EnableGuideModel.STEP_COUNT)
        assertEquals(listOf(1, 2, 3, 4), EnableGuideModel.stepNumbers())
    }

    @Test
    fun badgesAreNumberedInOrder() {
        assertEquals(listOf("1", "2", "3", "4"), EnableGuideModel.badgeLabels())
        assertEquals(EnableGuideModel.STEP_COUNT, EnableGuideModel.badgeLabels().size)
    }

    @Test
    fun validatesStepNumbers() {
        assertTrue(EnableGuideModel.isStepNumber(1))
        assertTrue(EnableGuideModel.isStepNumber(4))
        assertFalse(EnableGuideModel.isStepNumber(0))
        assertFalse(EnableGuideModel.isStepNumber(5))
        assertFalse(EnableGuideModel.isStepNumber(-1))
    }
}
