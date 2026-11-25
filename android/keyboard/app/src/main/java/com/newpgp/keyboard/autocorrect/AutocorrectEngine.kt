// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Vaibhav - https://github.com/thisisforlearn
package com.newpgp.keyboard.autocorrect

import java.util.Locale
import kotlin.math.abs
import kotlin.math.log10

/**
 * Testable, pure-Kotlin autocorrect engine: no Android dependencies, no I/O beyond the
 * optional [LearnedWords] persistence hook.
 *
 * Ranking combines edit distance (Damerau/OSA, so transpositions cost one edit) with
 * word frequency, a prefix-completion bonus, and a small bonus for learned words.
 * Short words are corrected conservatively to avoid mangling acronyms.
 */
class AutocorrectEngine(
    private val dictionary: WordFrequencyDictionary = WordFrequencyDictionary.builtIn(),
    private val learnedWords: LearnedWords = LearnedWords(),
    private val maxEditDistance: Int = 2
) {

    private enum class Source { DICTIONARY, LEARNED, TYPED }

    private data class Candidate(val word: String, val score: Double, val source: Source)

    /** Best-first suggestions. The typed word is always present so the user can keep it. */
    fun suggestions(typed: String, sentenceStart: Boolean = false, count: Int = 3): List<String> {
        if (typed.isBlank() || count <= 0) return emptyList()
        val ranked = rankedCandidates(typed.trim().lowercase(Locale.ROOT))
        val result = ArrayList<String>(count)
        for (candidate in ranked) {
            val display = TextProcessing.applyCase(candidate.word, sentenceStart)
            if (result.none { it.equals(display, ignoreCase = true) }) result.add(display)
            if (result.size == count) break
        }
        return result
    }

    /** Best replacement for [typed], or null when there is no safe correction to make. */
    fun correction(typed: String, sentenceStart: Boolean = false): String? {
        if (typed.isBlank()) return null
        val normalized = typed.trim().lowercase(Locale.ROOT)
        // Always capitalize the standalone pronoun, including when it is already correct.
        if (normalized == "i") return if (typed == "I") null else "I"
        // A known word is never "corrected" away.
        if (normalized in dictionary || normalized in learnedWords) return null
        val candidate = rankedCandidates(normalized).firstOrNull { it.word != normalized } ?: return null
        return TextProcessing.applyCase(candidate.word, sentenceStart)
    }

    fun learn(word: String) = learnedWords.learn(word)

    fun flushLearnedWords() = learnedWords.flush()

    fun learnedWordCounts(): Map<String, Int> = learnedWords.words()

    private fun rankedCandidates(normalized: String): List<Candidate> {
        val distanceLimit = when {
            normalized.length <= 2 -> 0
            normalized.length == 3 -> 1.coerceAtMost(maxEditDistance)
            else -> maxEditDistance.coerceAtLeast(0)
        }

        val candidates = ArrayList<Candidate>()
        for (word in dictionary.words()) {
            addCandidate(candidates, normalized, word, dictionary.frequency(word), distanceLimit, Source.DICTIONARY)
        }
        for ((word, frequency) in learnedWords.words()) {
            addCandidate(candidates, normalized, word, frequency, distanceLimit, Source.LEARNED)
        }
        // Always offer the literal typed word, but let a good correction outrank it.
        candidates.add(Candidate(normalized, TYPED_BASELINE, Source.TYPED))

        return candidates
            .sortedWith(compareByDescending<Candidate> { it.score }.thenBy { it.word })
            .distinctBy { it.word }
    }

    private fun addCandidate(
        candidates: MutableList<Candidate>,
        typed: String,
        word: String,
        frequency: Int,
        distanceLimit: Int,
        source: Source
    ) {
        if (word.isEmpty()) return
        if (word == typed) {
            candidates.add(Candidate(word, EXACT_MATCH_BONUS + score(frequency, 0, isPrefix = true), source))
            return
        }
        if (distanceLimit == 0 || abs(word.length - typed.length) > distanceLimit) return
        val distance = DamerauLevenshtein.distance(typed, word, distanceLimit)
        if (distance > distanceLimit) return
        val learnedBonus = if (source == Source.LEARNED) 0.75 else 0.0
        candidates.add(Candidate(word, score(frequency, distance, word.startsWith(typed)) + learnedBonus, source))
    }

    private fun score(frequency: Int, distance: Int, isPrefix: Boolean): Double {
        var value = log10(frequency.coerceAtLeast(1).toDouble() + 1.0) / (1.0 + 2.0 * distance)
        if (isPrefix) value += 1.5
        return value
    }

    private companion object {
        const val EXACT_MATCH_BONUS = 1000.0
        const val TYPED_BASELINE = 1.0
    }
}
