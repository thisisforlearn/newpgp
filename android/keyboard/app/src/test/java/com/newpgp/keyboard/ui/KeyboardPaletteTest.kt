// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Vaibhav - https://github.com/thisisforlearn
package com.newpgp.keyboard.ui

import com.newpgp.keyboard.KeyboardPalette
import com.newpgp.keyboard.KeyboardTheme
import org.junit.Assert.assertEquals
import org.junit.Test

/** Dark is the default; only an explicit UI_MODE_NIGHT_NO selects the light palette. */
class KeyboardPaletteTest {

    @Test
    fun onlyExplicitLightSystemModeUsesLightPalette() {
        assertEquals(KeyboardTheme.LIGHT, KeyboardPalette.themeForUiMode(UI_MODE_NIGHT_NO))
        assertEquals(KeyboardTheme.LIGHT, KeyboardPalette.themeForUiMode(UI_MODE_NIGHT_NO or 0x0C))
    }

    @Test
    fun darkIsDefaultForUndefinedYesAndOtherModes() {
        assertEquals(KeyboardTheme.DARK, KeyboardPalette.themeForUiMode(0))
        assertEquals(KeyboardTheme.DARK, KeyboardPalette.themeForUiMode(UI_MODE_NIGHT_YES))
        assertEquals(KeyboardTheme.DARK, KeyboardPalette.themeForUiMode(UI_MODE_NIGHT_YES or 0x0C))
        assertEquals(KeyboardTheme.DARK, KeyboardPalette.themeForUiMode(UI_MODE_NIGHT_UNDEFINED))
        assertEquals(KeyboardTheme.DARK, KeyboardPalette.themeForUiMode(UI_MODE_NIGHT_MASK))
    }

    private companion object {
        const val UI_MODE_NIGHT_MASK = 0x30
        const val UI_MODE_NIGHT_NO = 0x10
        const val UI_MODE_NIGHT_YES = 0x20
        const val UI_MODE_NIGHT_UNDEFINED = 0x00
    }
}
