// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Vaibhav - https://github.com/thisisforlearn
package com.newpgp.keyboard.autocorrect

import kotlin.math.abs

/**
 * Optimal String Alignment (restricted Damerau-Levenshtein) edit distance.
 *
 * Counts insertions, deletions, substitutions and adjacent transpositions with equal
 * cost, which is what is needed for keyboard typos such as "teh" -> "the".
 * When [maxDistance] is supplied the computation bails out early and returns
 * `maxDistance + 1` as a sentinel meaning "further than the threshold".
 */
object DamerauLevenshtein {

    fun distance(a: String, b: String, maxDistance: Int = Int.MAX_VALUE): Int {
        require(maxDistance >= 0) { "maxDistance must be non-negative" }
        if (a == b) return 0

        val overLimit = if (maxDistance == Int.MAX_VALUE) Int.MAX_VALUE else maxDistance + 1
        val m = a.length
        val n = b.length
        if (abs(m - n) > maxDistance) return overLimit
        if (m == 0) return n.coerceAtMost(overLimit)
        if (n == 0) return m.coerceAtMost(overLimit)

        // Three rolling rows: row i, row i-1 and row i-2 (needed for transpositions).
        var rowIMinus2 = IntArray(n + 1)
        var rowIMinus1 = IntArray(n + 1) { it }
        var rowI = IntArray(n + 1)

        for (i in 1..m) {
            rowI[0] = i
            for (j in 1..n) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                var best = minOf(
                    rowI[j - 1] + 1,       // insertion
                    rowIMinus1[j] + 1,     // deletion
                    rowIMinus1[j - 1] + cost // substitution / match
                )
                if (i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1]) {
                    best = minOf(best, rowIMinus2[j - 2] + 1) // adjacent transposition
                }
                rowI[j] = best
            }
            // Row minima never decrease across rows, so this early exit is safe.
            if (maxDistance != Int.MAX_VALUE && rowI.minOrNull()!! > maxDistance) return overLimit

            val oldest = rowIMinus2
            rowIMinus2 = rowIMinus1
            rowIMinus1 = rowI
            rowI = oldest
        }
        return rowIMinus1[n]
    }
}
