// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Vaibhav - https://github.com/thisisforlearn
package com.newpgp.keyboard.autocorrect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TextProcessingTest {

    @Test
    fun detectsSentenceStarts() {
        assertTrue(TextProcessing.isSentenceStart(""))
        assertTrue(TextProcessing.isSentenceStart("Hello. "))
        assertTrue(TextProcessing.isSentenceStart("Really?!  "))
        assertTrue(TextProcessing.isSentenceStart("First line\n"))
        assertFalse(TextProcessing.isSentenceStart("Hello "))
        assertFalse(TextProcessing.isSentenceStart("Mr. "))
        assertFalse(TextProcessing.isSentenceStart("Dr. "))
    }

    @Test
    fun capitalizesSentencesAndPronounI() {
        assertEquals("I", TextProcessing.applyCase("i", false))
        assertEquals("I", TextProcessing.applyCase("i", true))
        assertEquals("I'm", TextProcessing.applyCase("i'm", true))
        assertEquals("Hello", TextProcessing.applyCase("hello", true))
        assertEquals("hello", TextProcessing.applyCase("hello", false))
        assertEquals("HTTP", TextProcessing.applyCase("HTTP", true))
    }

    @Test
    fun punctuationSpacing() {
        assertEquals("", TextProcessing.separatorBefore("", "hello"))
        assertEquals("", TextProcessing.separatorBefore("Hi ", "there"))
        assertEquals(" ", TextProcessing.separatorBefore("Hi.", "there"))
        assertEquals(" ", TextProcessing.separatorBefore("Hi!", "there"))
        assertEquals("", TextProcessing.separatorBefore("Hi\n", "there"))
        assertEquals("", TextProcessing.separatorBefore("Hi (", "there"))
        assertEquals("", TextProcessing.separatorBefore("Hi", ","))
        assertEquals("", TextProcessing.separatorBefore("Hi", "!"))
        assertEquals("", TextProcessing.separatorBefore("Hi", ")"))
    }
}
