// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Vaibhav - https://github.com/thisisforlearn
package com.newpgp.keyboard.ai

import java.util.Locale

/**
 * Pure-Kotlin, synchronous "good enough" grammar pass.
 *
 * This is the instant path: no model, no I/O, no Android framework classes, no
 * allocation-heavy pipelines. It is deliberately conservative — it fixes only
 * high-confidence issues (a/an, standalone "i", common typos, doubled spaces and
 * words, sentence capitalization, terminal punctuation for multi-word sentences).
 * It is available before any model is loaded, which keeps inline rewriting instant.
 */
class GrammarRules {

    /** Full conservative grammar pass. Never throws for normal text. */
    fun rewrite(text: String): String {
        if (text.isEmpty()) return text
        val trimmed = text.trim()
        // URLs, emails, hashtags, emoji and code-like text are never rewritten.
        if (isNonProse(trimmed)) return trimmed
        var result = WHITESPACE.replace(trimmed, " ")
        result = fixTypos(result)
        result = fixArticles(result)
        result = fixDuplicateWords(result)
        result = fixSpacing(result)
        result = fixCapitalization(result)
        result = addTerminalPunctuation(result)
        return result
    }

    /**
     * Best-effort tone hint used only while a model-backed tone is loading or when
     * no model is available. Model output, when present, always wins.
     */
    fun rewriteWithTone(text: String, tone: Tone): String {
        var result = rewrite(text)
        result = when (tone) {
            Tone.GRAMMAR_ONLY -> result
            Tone.PROFESSIONAL -> replaceWords(result, PROFESSIONAL_MAP)
            Tone.FRIENDLY -> replaceWords(result, FRIENDLY_MAP)
            Tone.CONCISE -> removeFillers(result)
        }
        return WHITESPACE.replace(result.trim(), " ")
    }

    private fun fixTypos(input: String): String =
        replaceWords(input, TYPOS).let { STANDALONE_I.replace(it, "I") }

    /** Case-preserving token substitution using a single precompiled regex. */
    private fun replaceWords(input: String, map: Map<String, String>): String =
        WORD_TOKEN.replace(input) { match ->
            val replacement = map[match.value.lowercase(Locale.ROOT)] ?: return@replace match.value
            preserveCase(match.value, replacement)
        }

    private fun preserveCase(original: String, replacement: String): String = when {
        original == original.uppercase(Locale.ROOT) && original.any { it.isLetter() } ->
            replacement.uppercase(Locale.ROOT)
        original.firstOrNull()?.isUpperCase() == true ->
            replacement.replaceFirstChar { it.uppercase(Locale.ROOT) }
        else -> replacement
    }

    private fun fixArticles(input: String): String =
        ARTICLE_NEXT.replace(input) { match ->
            val article = match.groupValues[1]
            val gap = match.groupValues[2]
            val next = match.groupValues[3]
            val vowelSound = startsWithVowelSound(next)
            val wantsAn = article.equals("an", ignoreCase = true)
            if (vowelSound == wantsAn) {
                match.value
            } else {
                val changed = if (vowelSound) "an" else "a"
                preserveCase(article, changed) + gap + next
            }
        }

    /** Consonant/vowel *sound* heuristic; exceptions cover the usual English traps. */
    private fun startsWithVowelSound(word: String): Boolean {
        val lower = word.lowercase(Locale.ROOT)
        if (VOWEL_SOUND_EXCEPTIONS.any { lower.startsWith(it) }) return true
        if (CONSONANT_SOUND_EXCEPTIONS.any { lower.startsWith(it) }) return false
        return lower.firstOrNull()?.let { it in charArrayOf('a', 'e', 'i', 'o', 'u') } ?: false
    }

    private fun fixDuplicateWords(input: String): String =
        DUPLICATE_WORD.replace(input) { match ->
            val word = match.groupValues[1]
            if (word.lowercase(Locale.ROOT) in KEEP_DUPLICATES) match.value else word
        }

    private fun fixSpacing(input: String): String {
        var result = SPACE_BEFORE_PUNCT.replace(input, "$1")
        result = MISSING_SPACE_AFTER_COMMA.replace(result, "$1 ")
        // Never join a protocol ("http://") or decimal point.
        result = result.replace(Regex("""\s+://"""), "://")
        return result
    }

    private fun fixCapitalization(input: String): String {
        if (input.isEmpty()) return input
        val out = StringBuilder(input.length)
        var capitalizeNext = true
        var sentenceEndPending = false
        for (ch in input) {
            when {
                capitalizeNext && ch.isLowerCase() -> {
                    out.append(ch.uppercaseChar())
                    capitalizeNext = false
                    sentenceEndPending = false
                }
                ch.isLetterOrDigit() -> {
                    out.append(ch)
                    capitalizeNext = false
                    sentenceEndPending = false
                }
                else -> {
                    out.append(ch)
                    // Start a new sentence after . ! ? followed by whitespace.
                    when {
                        ch == '.' || ch == '!' || ch == '?' -> sentenceEndPending = true
                        ch.isWhitespace() && sentenceEndPending -> {
                            capitalizeNext = true
                            sentenceEndPending = false
                        }
                        !ch.isWhitespace() -> sentenceEndPending = false
                    }
                }
            }
        }
        return out.toString()
    }

    /**
     * Cheap allocation-free check for text that must be left byte-for-byte alone:
     * URLs, emails, @handles, hashtags, emoji and code-like snippets.
     */
    private fun isNonProse(text: String): Boolean {
        if (text.startsWith("http", ignoreCase = true) ||
            text.startsWith("www.", ignoreCase = true) ||
            text.startsWith("ftp", ignoreCase = true)
        ) return true
        for (ch in text) {
            if (ch == '@' || ch == '#' || ch == '/' || ch == '\\' || ch == '`' ||
                ch == '<' || ch == '>' || ch == '{' || ch == '}' || ch == '[' || ch == ']' ||
                ch == '=' || ch == '~' || ch == '|' || Character.isSurrogate(ch)
            ) return true
            val type = Character.getType(ch)
            if (type == Character.OTHER_SYMBOL || type == Character.MATH_SYMBOL ||
                type == Character.MODIFIER_SYMBOL
            ) return true
        }
        return false
    }

    /**
     * Add a single terminal period to a complete, plain-text sentence.
     *
     * The rule: after trimming, require at least two words, a final character that
     * is a letter/digit (not punctuation), no existing terminal `.`/`!`/`?`, and no
     * interior period (which signals an abbreviation such as "Mr.", a decimal, or
     * a URL). Newlines, emoji, symbols and code-like characters are left alone.
     * A single pass over the string; no regex is compiled or run per keystroke.
     */
    private fun addTerminalPunctuation(input: String): String {
        val text = input.trim()
        if (text.length < 4) return input
        val last = text.last()
        if (last == '.' || last == '!' || last == '?') return input // never double-punctuate

        var words = 0
        var inWord = false
        for (index in text.indices) {
            val ch = text[index]
            when {
                ch.isLetterOrDigit() -> {
                    if (!inWord) {
                        words++
                        inWord = true
                    }
                }
                ch == '\'' || ch == '-' -> Unit // contractions and hyphenated words stay one word
                ch == ' ' || ch == '\t' || ch == '\u00A0' -> inWord = false
                ch == '.' -> {
                    // A non-final period means abbreviation, decimal or URL — leave it alone.
                    if (index != text.lastIndex) return input
                    inWord = false
                }
                ch in ",;:!?\"" -> inWord = false
                else -> return input // newline, emoji, symbol, code — not plain prose
            }
        }
        if (words < 2 || !last.isLetterOrDigit()) return input
        return "$text."
    }

    private fun removeFillers(input: String): String {
        var result = FILLER_PHRASES.replace(input, " ")
        result = FILLER_WORDS.replace(result, "")
        return WHITESPACE.replace(result, " ").trim().let { fixCapitalization(it) }
    }

    private companion object {
        val WHITESPACE = Regex("""[ \t\u00A0]+""")
        val STANDALONE_I = Regex("""\bi\b""")
        val SPACE_BEFORE_PUNCT = Regex("""[ \t]+([,.;:!?])""")
        val MISSING_SPACE_AFTER_COMMA = Regex("""([,;])(?=[A-Za-z])""")

        // Words that begin with a vowel letter but a consonant sound, and vice versa.
        val VOWEL_SOUND_EXCEPTIONS = listOf(
            "hour", "honest", "honor", "heir", "herb", "oeuvre", "euler"
        )
        // Vowel letters pronounced with a "y" or "w" sound take "a", not "an".
        val CONSONANT_SOUND_EXCEPTIONS = listOf(
            "one", "once", "university", "uniform", "unique", "united", "useful",
            "user", "ukulele", "eucalyptus", "ubiquitous", "unicorn", "utensil",
            "european", "europe"
        )
        val ARTICLE_NEXT = Regex("""\b(a|an)([ \t]+)([A-Za-z][A-Za-z']*)\b""", RegexOption.IGNORE_CASE)
        val DUPLICATE_WORD = Regex("""\b([A-Za-z]{2,})[ \t]+\1\b""", RegexOption.IGNORE_CASE)
        val KEEP_DUPLICATES = setOf("had", "that", "is", "was", "very", "no")

        val WORD_TOKEN = Regex("""\b[A-Za-z][A-Za-z']*\b""")

        val TYPOS = mapOf(
            "teh" to "the", "recieve" to "receive", "recieved" to "received",
            "seperate" to "separate", "seperated" to "separated", "occured" to "occurred",
            "occurence" to "occurrence", "definately" to "definitely", "alot" to "a lot",
            "doesnt" to "doesn't", "dont" to "don't", "isnt" to "isn't", "arent" to "aren't",
            "cant" to "can't", "wont" to "won't", "shouldnt" to "shouldn't",
            "couldnt" to "couldn't", "wouldnt" to "wouldn't", "hasnt" to "hasn't",
            "havent" to "haven't", "im" to "I'm", "ive" to "I've", "ill" to "I'll",
            "thier" to "their", "freind" to "friend", "wierd" to "weird",
            "achive" to "achieve", "adress" to "address", "apropriate" to "appropriate",
            "becuase" to "because",
            "calender" to "calendar", "comming" to "coming", "enviroment" to "environment",
            "goverment" to "government", "happend" to "happened", "independant" to "independent",
            "lenght" to "length", "neccessary" to "necessary", "occassion" to "occasion",
            "publically" to "publicly", "recomend" to "recommend", "sucess" to "success",
            "tommorow" to "tomorrow", "untill" to "until", "wich" to "which",
            "writting" to "writing", "retrive" to "retrieve", "scentence" to "sentence"
        )

        val PROFESSIONAL_MAP = mapOf(
            "hey" to "hello", "hiya" to "hello", "guys" to "everyone",
            "gonna" to "going to", "wanna" to "want to", "u" to "you", "ur" to "your",
            "lol" to "", "ok" to "OK", "thx" to "thank you"
        )
        val FRIENDLY_MAP = mapOf(
            "hello" to "hi", "sincerely" to "best", "regards" to "cheers",
            "utilize" to "use", "commence" to "start"
        )
        val FILLER_PHRASES = Regex(
            """\b(in order to|kind of|sort of|at this point in time)\b""",
            RegexOption.IGNORE_CASE
        )
        val FILLER_WORDS = Regex("""\b(just|really|actually|basically|literally|simply)\b""", RegexOption.IGNORE_CASE)
    }
}
