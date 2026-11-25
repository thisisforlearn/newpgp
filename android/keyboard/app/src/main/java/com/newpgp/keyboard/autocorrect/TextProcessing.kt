// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Vaibhav - https://github.com/thisisforlearn
package com.newpgp.keyboard.autocorrect

import java.util.Locale

/** Sentence casing and punctuation-spacing helpers used by the IME. */
object TextProcessing {

    private val ABBREVIATIONS = setOf("mr", "mrs", "ms", "dr", "st", "vs", "etc", "e.g", "i.e", "jr", "sr")

    private const val OPENING_PUNCTUATION = "([{\"'“‘"
    private const val ATTACHED_PUNCTUATION = ".,!?;:)]}\"'”’%/"

    /** True at the very beginning of a field, after a newline, or after sentence-ending punctuation. */
    fun isSentenceStart(contextBefore: String): Boolean {
        val trimmed = contextBefore.trimEnd()
        if (trimmed.isEmpty()) return true
        if (trimmed.last() == '\n') return true
        val lower = trimmed.lowercase(Locale.ROOT)
        if (ABBREVIATIONS.any { lower.endsWith("$it.") }) return false
        return trimmed.last() in ".!?"
    }

    /** Sentence-start capitalization and the always-capital pronoun "I". */
    fun applyCase(word: String, sentenceStart: Boolean): String {
        if (word.isEmpty()) return word
        if (word.equals("i", ignoreCase = true)) return "I"
        return if (sentenceStart) {
            word.replaceFirstChar { if (it.isLowerCase()) it.titlecaseChar() else it }
        } else {
            word
        }
    }

    /**
     * Returns the separator to insert before [nextWord] given the text already in the field.
     * Opening punctuation and punctuation attached to the previous word need no space.
     */
    fun separatorBefore(contextBefore: String, nextWord: String): String {
        if (contextBefore.isEmpty()) return ""
        val last = contextBefore.last()
        if (last.isWhitespace()) return ""
        if (OPENING_PUNCTUATION.contains(last)) return ""
        val first = nextWord.firstOrNull() ?: return ""
        if (ATTACHED_PUNCTUATION.contains(first)) return ""
        return " "
    }
}
