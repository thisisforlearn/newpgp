// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Vaibhav - https://github.com/thisisforlearn
package com.newpgp.keyboard.autocorrect

import java.util.Locale

/**
 * In-memory user dictionary. Persistence is optional and delegated to [Persistence];
 * the engine itself never writes typed text to disk.
 */
class LearnedWords(private val persistence: Persistence? = null) {

    interface Persistence {
        fun load(): Map<String, Int>
        fun save(words: Map<String, Int>)
    }

    companion object {
        private const val MAX_STORED_WORDS = 2000
        private const val MAX_FREQUENCY = 100
    }

    private val counts = LinkedHashMap<String, Int>()

    init {
        persistence?.load()?.forEach { (word, count) ->
            val key = word.lowercase(Locale.ROOT).trim()
            if (key.isNotEmpty()) counts[key] = count.coerceIn(1, MAX_FREQUENCY)
        }
    }

    /** Records a successfully used word. Caps prevent a single word dominating ranking. */
    fun learn(word: String) {
        val key = word.lowercase(Locale.ROOT).trim()
        if (key.isEmpty()) return
        if (!counts.containsKey(key) && counts.size >= MAX_STORED_WORDS) {
            counts.remove(counts.keys.first())
        }
        counts[key] = minOf((counts[key] ?: 0) + 1, MAX_FREQUENCY)
    }

    fun frequency(word: String): Int = counts[word.lowercase(Locale.ROOT)] ?: 0

    operator fun contains(word: String): Boolean = counts.containsKey(word.lowercase(Locale.ROOT))

    fun words(): Map<String, Int> = counts.toMap()

    fun prefixMatches(prefix: String, limit: Int = 3): List<WeightedWord> {
        if (prefix.isEmpty() || limit <= 0) return emptyList()
        val normalized = prefix.lowercase(Locale.ROOT)
        return counts
            .filterKeys { it.startsWith(normalized) }
            .map { (word, frequency) -> WeightedWord(word, frequency) }
            .sortedWith(compareByDescending<WeightedWord> { it.frequency }.thenBy { it.word })
            .take(limit)
    }

    /** Explicitly persist the learned-word counts through the supplied hook. */
    fun flush() {
        persistence?.save(counts.toMap())
    }

    fun clear() {
        counts.clear()
        persistence?.save(emptyMap())
    }
}
