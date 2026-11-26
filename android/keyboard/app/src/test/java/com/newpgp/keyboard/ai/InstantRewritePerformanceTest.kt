// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Vaibhav - https://github.com/thisisforlearn
package com.newpgp.keyboard.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InstantRewritePerformanceTest {

    @Test
    fun grammarOnlyRewriteIsFastEnoughForInlineUse() {
        val rules = GrammarRules()
        val input = "i recieve teh adress and an car"
        val expected = rules.rewrite(input)

        repeat(WARMUP) { rules.rewrite(input) }
        val start = System.nanoTime()
        repeat(ITERATIONS) { assertEquals(expected, rules.rewrite(input)) }
        val averageMicros = (System.nanoTime() - start) / 1_000L / ITERATIONS
        assertTrue("average rewrite was $averageMicros us", averageMicros < MAX_AVERAGE_MICROS)
    }

    @Test
    fun engineGrammarPathNeedsNoModelAndIsFast() {
        val created = ArrayList<FakeToneEngine>()
        val engine = ToneEngine(
            modelRoot = "/models",
            modelSource = FakeModelSource(),
            backendFactory = { _, _ -> FakeToneEngine().also { created.add(it) } }
        )
        try {
            repeat(WARMUP) { engine.rewrite("hello world", Tone.GRAMMAR_ONLY) }
            val start = System.nanoTime()
            repeat(ITERATIONS) { engine.rewrite("hello world", Tone.GRAMMAR_ONLY) }
            val averageMicros = (System.nanoTime() - start) / 1_000L / ITERATIONS
            assertTrue("average rewrite was $averageMicros us", averageMicros < MAX_AVERAGE_MICROS)
            assertTrue(created.isEmpty())
            assertFalse(engine.isLoaded())
        } finally {
            engine.close()
        }
    }

    private companion object {
        const val WARMUP = 200
        const val ITERATIONS = 2_000
        const val MAX_AVERAGE_MICROS = 5_000L // 5 ms: generous on slow CI, still "instant"
    }
}
