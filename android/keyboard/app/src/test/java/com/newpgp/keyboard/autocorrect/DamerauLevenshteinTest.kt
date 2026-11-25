// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Vaibhav - https://github.com/thisisforlearn
package com.newpgp.keyboard.autocorrect

import org.junit.Assert.assertEquals
import org.junit.Test

class DamerauLevenshteinTest {

    @Test
    fun identicalWordsHaveZeroDistance() {
        assertEquals(0, DamerauLevenshtein.distance("the", "the"))
        assertEquals(0, DamerauLevenshtein.distance("", ""))
    }

    @Test
    fun adjacentTranspositionsCostOneEdit() {
        assertEquals(1, DamerauLevenshtein.distance("teh", "the"))
        assertEquals(1, DamerauLevenshtein.distance("wrold", "world"))
        assertEquals(1, DamerauLevenshtein.distance("ab", "ba"))
    }

    @Test
    fun insertionsDeletionsAndSubstitutions() {
        assertEquals(1, DamerauLevenshtein.distance("cat", "cats"))
        assertEquals(1, DamerauLevenshtein.distance("cats", "cat"))
        assertEquals(1, DamerauLevenshtein.distance("cat", "cot"))
        assertEquals(3, DamerauLevenshtein.distance("kitten", "sitting"))
    }

    @Test
    fun emptyStrings() {
        assertEquals(3, DamerauLevenshtein.distance("", "abc"))
        assertEquals(2, DamerauLevenshtein.distance("ab", ""))
    }

    @Test
    fun maxDistanceReturnsSentinelWhenTooFar() {
        assertEquals(3, DamerauLevenshtein.distance("abc", "xyz", 2))
        assertEquals(1, DamerauLevenshtein.distance("teh", "the", 1))
        assertEquals(2, DamerauLevenshtein.distance("a", "bbbb", 1))
    }

    @Test(expected = IllegalArgumentException::class)
    fun negativeThresholdIsRejected() {
        DamerauLevenshtein.distance("a", "b", -1)
    }
}
