// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Vaibhav - https://github.com/thisisforlearn
package com.newpgp.keyboard.autocorrect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LearnedWordsTest {

    @Test
    fun learnIncrementsCountAndIsCaseInsensitive() {
        val words = LearnedWords()
        words.learn("NewPGP")
        words.learn("newpgp")
        assertEquals(2, words.frequency("NEWPGP"))
        assertTrue("newpgp" in words)
        assertFalse("other" in words)
    }

    @Test
    fun learnedWordsContributePrefixMatches() {
        val words = LearnedWords()
        words.learn("newpgp")
        words.learn("newt")
        val matches = words.prefixMatches("new")
        assertEquals("newpgp", matches.first().word)
        assertEquals(2, matches.size)
    }

    @Test
    fun emptyWordsAreIgnored() {
        val words = LearnedWords()
        words.learn("   ")
        assertTrue(words.words().isEmpty())
    }

    @Test
    fun persistenceHookRoundTripsCounts() {
        val store = object : LearnedWords.Persistence {
            var saved: Map<String, Int> = emptyMap()
            override fun load(): Map<String, Int> = saved
            override fun save(words: Map<String, Int>) {
                saved = words
            }
        }

        val first = LearnedWords(store)
        first.learn("newpgp")
        first.flush()

        val reloaded = LearnedWords(store)
        assertEquals(1, reloaded.frequency("newpgp"))

        reloaded.clear()
        assertTrue(store.saved.isEmpty())
    }
}
