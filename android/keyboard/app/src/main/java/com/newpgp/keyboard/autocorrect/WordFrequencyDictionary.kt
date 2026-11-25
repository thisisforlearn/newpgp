// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Vaibhav - https://github.com/thisisforlearn
package com.newpgp.keyboard.autocorrect

import java.util.Locale

/** A lowercase word and its relative usage frequency (higher = more common). */
data class WeightedWord(val word: String, val frequency: Int)

/**
 * Immutable word-frequency dictionary with prefix lookup.
 *
 * All lookups are case-insensitive and locale-independent (ROOT locale), so an
 * autocorrection never depends on the device language settings.
 */
class WordFrequencyDictionary private constructor(private val frequencies: Map<String, Int>) {

    val size: Int get() = frequencies.size

    fun frequency(word: String): Int = frequencies[word.lowercase(Locale.ROOT)] ?: 0

    operator fun contains(word: String): Boolean = frequencies.containsKey(word.lowercase(Locale.ROOT))

    fun words(): Set<String> = frequencies.keys

    /** Most frequent words starting with [prefix], highest frequency first. */
    fun prefixMatches(prefix: String, limit: Int = 3): List<WeightedWord> {
        if (prefix.isEmpty() || limit <= 0) return emptyList()
        val normalized = prefix.lowercase(Locale.ROOT)
        return frequencies
            .filterKeys { it.startsWith(normalized) }
            .map { (word, frequency) -> WeightedWord(word, frequency) }
            .sortedWith(compareByDescending<WeightedWord> { it.frequency }.thenBy { it.word })
            .take(limit)
    }

    companion object {
        fun from(entries: Iterable<WeightedWord>): WordFrequencyDictionary {
            val map = LinkedHashMap<String, Int>()
            for (entry in entries) {
                val key = entry.word.lowercase(Locale.ROOT)
                map[key] = maxOf(map[key] ?: 0, entry.frequency)
            }
            return WordFrequencyDictionary(map)
        }

        fun builtIn(): WordFrequencyDictionary = from(BUILT_IN)

        private val BUILT_IN: List<WeightedWord> by lazy { parse(RAW_BUILT_IN_WORDS) }

        private fun parse(raw: String): List<WeightedWord> = raw
            .trim()
            .lines()
            .flatMap { it.trim().split(WHITESPACE) }
            .filter { it.isNotEmpty() }
            .map { token ->
                val parts = token.split(':')
                require(parts.size == 2) { "Malformed dictionary entry: $token" }
                WeightedWord(parts[0], parts[1].toInt())
            }

        private val WHITESPACE = Regex("\\s+")

        private const val RAW_BUILT_IN_WORDS = """
            the:100000 be:90000 to:85000 of:80000 and:75000 a:70000 in:65000 is:63000
            that:60000 it:58000 have:55000 for:53000 not:51000 on:49000 with:47000 he:45000
            as:43000 you:41000 do:39000 at:37000 this:35000 but:34000 his:33000 by:32000
            from:31000 they:30000 we:29000 say:28000 her:27000 she:26000 or:25000 an:24000
            will:23000 my:22000 one:21000 all:20000 would:19000 there:18000 their:17500
            what:17000 so:16000 up:15500 out:15000 if:14500 about:14000 who:13500 get:13000
            which:12500 go:12000 me:11500 when:11000 make:10500 can:10000 like:9500 time:9000
            no:8500 just:8000 him:7500 know:7000 take:6500 people:6000 into:5800 year:5600
            your:5400 good:5200 some:5000 could:4800 them:4600 see:4400 other:4200 than:4000
            then:3800 now:3600 look:3400 only:3200 come:3000 its:2900 over:2800 think:2700
            also:2600 back:2500 after:2400 use:2300 two:2200 how:2100 our:2000 work:1900
            first:1800 well:1700 way:1600 even:1500 new:1400 want:1300 because:1200 any:1100
            these:1050 give:1000 day:950 most:900 us:850 hello:800 world:800 test:700
            testing:650 quick:600 brown:550 fox:500 jumps:450 lazy:400 dog:420 cat:420
            cats:380 dogs:380 run:360 running:350 ran:340 walk:320 walked:300 playing:290
            played:280 music:270 song:260 songs:250 book:240 books:230 page:220 pages:210
            word:200 words:190 letter:180 letters:170 sentence:160 sentences:150 number:140
            numbers:130 code:120 codes:110 app:100 apps:95 phone:90 computer:85 screen:80
            internet:75 online:70 offline:65 download:60 upload:58 link:55 links:50 click:48
            type:45 typed:44 typing:43 keyboard:42 spell:40 spelling:39 correct:38 correction:37
            wrong:36 right:35 true:34 false:33 free:32 source:31 project:30 build:29 built:28
            version:27 release:26 github:25 pgp:24 gpg:23 key:22 keys:21 private:20 public:19
            secure:18 security:17 email:16 mail:15 send:14 sent:13 received:12 file:11 files:10
            text:10 note:9 notes:8 password:8 passphrase:7 sign:7 signed:6 signature:6 verify:6
            verified:5 trust:5 privacy:5 safe:4 safety:4 please:4 thanks:4 thank:4 yes:4 yeah:4
            ok:4 okay:4 maybe:4 really:4 very:4 much:4 many:4 more:4 less:4 best:4 better:4
            great:4 nice:4 happy:4 sorry:4 hi:4 hey:4 bye:4 morning:4 evening:4 night:4 today:4
            tomorrow:4 yesterday:4 week:4 month:4 minute:4 hour:4 friend:4 friends:4 family:4
            love:4 need:4 feel:4 idea:4 something:4 anything:4 everything:4 nothing:4 someone:4
            anyone:4 everyone:4 thing:4 things:4 place:4 places:4 home:4 here:4 where:4 why:4
            while:4 before:4 during:4 since:4 until:4 against:4 between:4 through:4 under:4
            above:4 below:4 near:4 far:4 again:4 once:4 never:4 always:4 often:4 sometimes:4
            usually:4 still:4 yet:4 already:4 almost:4 enough:4 too:4 together:4 alone:4
            away:4 around:4 along:4 across:4 behind:4 beyond:4 inside:4 outside:4 open:4
            close:4 closed:4 start:4 started:4 stop:4 stopped:4 end:4 ended:4 continue:4
            try:4 tried:4 trying:4 help:4 helped:4 call:4 called:4 write:4 wrote:4 written:4
            read:4 reading:4 speak:4 said:4 tells:4 told:4 ask:4 asked:4 answer:4 answered:4
            question:4 questions:4 problem:4 problems:4 solution:4 solutions:4 message:4
            messages:4 encrypt:4 encrypted:4 encryption:4 newpgp:4
        """
    }
}
