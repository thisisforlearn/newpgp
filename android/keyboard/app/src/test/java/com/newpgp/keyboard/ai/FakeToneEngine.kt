// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Vaibhav - https://github.com/thisisforlearn
package com.newpgp.keyboard.ai

import java.io.ByteArrayInputStream
import java.io.InputStream

/** Fake model backend used by the JVM tests. Never touches a real interpreter. */
class FakeToneEngine(
    override val id: String = "fake",
    private val transform: (String, Tone) -> String = { text, tone -> "$tone:$text" },
    initiallyReady: Boolean = true
) : RewriteBackend {

    @Volatile
    private var ready = initiallyReady

    val calls = ArrayList<Pair<String, Tone>>()
    var closeCalls = 0

    override fun isReady(): Boolean = ready

    override fun rewrite(text: String, tone: Tone): String {
        synchronized(this) { calls.add(text to tone) }
        return transform(text, tone)
    }

    override fun close() {
        closeCalls++
        ready = false
    }

    fun setReady(value: Boolean) {
        ready = value
    }
}

/** Test model source: files "exist" without any I/O. */
class FakeModelSource : ModelSource {
    override fun open(path: String): InputStream = ByteArrayInputStream(ByteArray(0))
    override fun exists(path: String): Boolean = true
    override fun sibling(path: String, name: String): String = name
}

/** Records only event codes so tests can prove no plaintext reaches logging. */
class RecordingAiLogger : AiLogger {
    val codes = ArrayList<String>()
    override fun event(code: String) {
        synchronized(this) { codes.add(code) }
    }
    override fun error(code: String) {
        synchronized(this) { codes.add(code) }
    }
}
