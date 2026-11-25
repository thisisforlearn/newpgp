// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Vaibhav - https://github.com/thisisforlearn
package com.newpgp.keyboard.autocorrect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AutocorrectEngineTest {

    private val engine = AutocorrectEngine()

    @Test
    fun correctsCommonMisspellings() {
        assertEquals("the", engine.correction("teh"))
        assertEquals("world", engine.correction("wrold"))
        assertEquals("hello", engine.correction("helllo"))
    }

    @Test
    fun suggestionsAreBestFirstAndKeepTypedWord() {
        val suggestions = engine.suggestions("teh")
        assertEquals("the", suggestions.first())
        assertTrue(suggestions.contains("teh"))
        assertTrue(suggestions.size <= 3)
    }

    @Test
    fun capitalIIsAlwaysSuggested() {
        assertEquals("I", engine.correction("i"))
        assertEquals("I", engine.suggestions("i").first())
        assertNull(engine.correction("I"))
    }

    @Test
    fun appliesSentenceStartCapitalization() {
        assertEquals("The", engine.correction("teh", sentenceStart = true))
        assertEquals("Hello", engine.suggestions("hello", sentenceStart = true).first())
    }

    @Test
    fun knownWordsAreLeftAlone() {
        assertNull(engine.correction("the"))
        assertNull(engine.correction("hello"))
        assertNull(engine.correction("world"))
    }

    @Test
    fun unknownWordsAreLeftAlone() {
        assertNull(engine.correction("qwertyuiop"))
        assertEquals("qwertyuiop", engine.suggestions("qwertyuiop").first())
    }

    @Test
    fun respectsMaximumEditDistance() {
        assertNull(AutocorrectEngine(maxEditDistance = 0).correction("teh"))
        assertEquals("the", AutocorrectEngine(maxEditDistance = 1).correction("teh"))
    }

    @Test
    fun learnedWordsAreSuggestedAndNeverCorrectedAway() {
        val learned = LearnedWords()
        learned.learn("newpgp")
        val withLearnedWord = AutocorrectEngine(learnedWords = learned)

        assertEquals("newpgp", withLearnedWord.suggestions("newpg").first())
        assertEquals("newpgp", withLearnedWord.correction("newpg"))
        assertNull(withLearnedWord.correction("newpgp"))
    }

    @Test
    fun learnsCorrectionsViaPublicApi() {
        val learned = LearnedWords()
        val engineWithLearning = AutocorrectEngine(learnedWords = learned)
        val correction = engineWithLearning.correction("teh")
        assertEquals("the", correction)
        engineWithLearning.learn(correction!!)
        assertEquals(1, engineWithLearning.learnedWordCounts()["the"])
    }

    @Test
    fun blankInputProducesNoSuggestions() {
        assertTrue(engine.suggestions("").isEmpty())
        assertTrue(engine.suggestions("   ").isEmpty())
        assertNull(engine.correction(""))
    }
}
