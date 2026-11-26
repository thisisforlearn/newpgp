// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Vaibhav - https://github.com/thisisforlearn
package com.newpgp.keyboard

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InputTypePolicyTest {

    @Test
    fun detectsAllPasswordVariations() {
        assertTrue(InputTypePolicy.isPassword(TYPE_TEXT or PASSWORD))
        assertTrue(InputTypePolicy.isPassword(TYPE_TEXT or VISIBLE_PASSWORD))
        assertTrue(InputTypePolicy.isPassword(TYPE_TEXT or WEB_PASSWORD))
        assertTrue(InputTypePolicy.isPassword(TYPE_NUMBER or NUMBER_PASSWORD))
        assertFalse(InputTypePolicy.isPassword(TYPE_TEXT))
    }

    @Test
    fun noSuggestionsAndPasswordsSuppressSuggestions() {
        assertTrue(InputTypePolicy.suppressesSuggestions(TYPE_TEXT or NO_SUGGESTIONS))
        assertTrue(InputTypePolicy.suppressesSuggestions(TYPE_TEXT or PASSWORD))
        assertFalse(InputTypePolicy.suppressesSuggestions(TYPE_TEXT))
    }

    @Test
    fun detectsNullAndMultilineEditors() {
        assertTrue(InputTypePolicy.isNullType(0x00))
        assertFalse(InputTypePolicy.isNullType(TYPE_TEXT))
        assertTrue(InputTypePolicy.isMultiLine(TYPE_TEXT or MULTI_LINE))
        assertFalse(InputTypePolicy.isMultiLine(TYPE_TEXT))
    }

    private companion object {
        const val TYPE_TEXT = 0x01
        const val TYPE_NUMBER = 0x02
        const val PASSWORD = 0x80
        const val VISIBLE_PASSWORD = 0x90
        const val WEB_PASSWORD = 0xe0
        const val NUMBER_PASSWORD = 0x10
        const val NO_SUGGESTIONS = 0x00080000
        const val MULTI_LINE = 0x00020000
    }
}
