// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Vaibhav - https://github.com/thisisforlearn
package com.newpgp.keyboard.ai

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AiLoggingTest {

    @Test
    fun logsOnlyFixedCodesNeverTypedText() {
        val sentinel = "TOPSECRET-SENTINEL-12345"
        val logger = RecordingAiLogger()
        val engine = ToneEngine(
            modelRoot = "/models",
            modelSource = FakeModelSource(),
            backendFactory = { _, _ -> FakeToneEngine(transform = { text, _ -> "rewritten:$text" }) },
            logger = logger
        )
        try {
            engine.load()
            engine.rewrite(sentinel, Tone.PROFESSIONAL)
            engine.rewrite(sentinel, Tone.GRAMMAR_ONLY)
            engine.setModel("unknown-model")
            engine.unload()

            assertTrue(logger.codes.isNotEmpty())
            for (code in logger.codes) {
                assertFalse("plaintext in log code: $code", code.contains(sentinel))
                assertFalse("rewritten text in log code: $code", code.contains("rewritten"))
                assertTrue("non-code log entry: $code", CODE_PATTERN.matches(code))
            }
        } finally {
            engine.close()
        }
    }

    private companion object {
        val CODE_PATTERN = Regex("""[a-z][a-z0-9._]*""")
    }
}
