// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Vaibhav - https://github.com/thisisforlearn
package com.newpgp.keyboard.autocorrect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WordFrequencyDictionaryTest {

    private val dictionary = WordFrequencyDictionary.builtIn()

    @Test
    fun builtInDictionaryIsSubstantial() {
        assertTrue(dictionary.size > 200)
        assertTrue("the" in dictionary)
        assertTrue("world" in dictionary)
        assertFalse("qwertyuiop" in dictionary)
    }

    @Test
    fun lookupsAreCaseInsensitive() {
        assertEquals(100_000, dictionary.frequency("THE"))
        assertEquals(dictionary.frequency("hello"), dictionary.frequency("Hello"))
    }

    @Test
    fun prefixMatchesAreOrderedByFrequencyAndLimited() {
        val matches = dictionary.prefixMatches("th", 3)
        assertEquals(listOf("the", "that", "this"), matches.map { it.word })
        assertTrue(matches[0].frequency >= matches[1].frequency)
        assertTrue(dictionary.prefixMatches("the", 25).all { it.word.startsWith("the") })
    }

    @Test
    fun emptyPrefixHasNoMatches() {
        assertEquals(emptyList<WeightedWord>(), dictionary.prefixMatches("", 3))
    }

    @Test
    fun customDictionaryKeepsHighestFrequencyForDuplicates() {
        val custom = WordFrequencyDictionary.from(
            listOf(WeightedWord("alpha", 4), WeightedWord("ALPHA", 9))
        )
        assertEquals(9, custom.frequency("alpha"))
        assertEquals(1, custom.size)
    }
}
