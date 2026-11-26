// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Vaibhav - https://github.com/thisisforlearn
package com.newpgp.keyboard.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GrammarRulesTest {

    private val rules = GrammarRules()

    @Test
    fun fixesTyposCapitalizationAndPunctuation() {
        assertEquals("I receive the appropriate address.", rules.rewrite("i recieve teh apropriate adress"))
    }

    @Test
    fun correctsArticlesBySoundNotJustLetter() {
        assertEquals("I saw an apple and a car.", rules.rewrite("i saw a apple and an car"))
        assertEquals("An hour is enough.", rules.rewrite("an hour is enough"))
        assertEquals("A university is nearby.", rules.rewrite("a university is nearby"))
        assertEquals("An honest answer matters.", rules.rewrite("a honest answer matters"))
    }

    @Test
    fun normalizesSpacingAndDoubledWords() {
        assertEquals("This is the test.", rules.rewrite("this  is   the the test"))
        assertEquals("Hello, world.", rules.rewrite("hello , world"))
        assertEquals("Hello world.", rules.rewrite("hello world"))
    }

    @Test
    fun addsTerminalPeriodOnlyToCompleteProseSentences() {
        // Two words and no terminal mark -> exactly one period is appended.
        assertEquals("Hello world.", rules.rewrite("Hello world"))
        // Already-correct text is never double-punctuated.
        assertEquals("Hello world.", rules.rewrite("Hello world."))
        assertEquals("Are you ok?", rules.rewrite("are you ok?"))
        assertEquals("Are you ok?", rules.rewrite("Are you ok?"))
        // An interior period (abbreviation or decimal) means "leave it alone".
        assertEquals("See Mr. Smith", rules.rewrite("see Mr. Smith"))
        assertEquals("See Dr. Jones", rules.rewrite("see Dr. Jones"))
        assertEquals("Meet me at 3.5", rules.rewrite("meet me at 3.5"))
        // URLs, emails, hashtags, emoji and code-like text are untouched.
        assertEquals("https://example.com", rules.rewrite("https://example.com"))
        assertEquals("mail me at a@b.com", rules.rewrite("mail me at a@b.com"))
        assertEquals("#hello", rules.rewrite("#hello"))
        assertEquals("hello \u2603", rules.rewrite("hello \u2603"))
        assertEquals("val x = 1", rules.rewrite("val x = 1"))
    }

    @Test
    fun leavesShortChatRepliesAlone() {
        assertEquals("Ok", rules.rewrite("ok"))
        assertEquals("", rules.rewrite(""))
    }

    @Test
    fun toneFallbackUsesConservativeHints() {
        assertTrue(rules.rewriteWithTone("hey , guys", Tone.PROFESSIONAL).startsWith("Hello, everyone"))
        assertTrue(rules.rewriteWithTone("i really like it", Tone.CONCISE).contains("like"))
        assertTrue(!rules.rewriteWithTone("i really like it", Tone.CONCISE).contains("really"))
    }
}
