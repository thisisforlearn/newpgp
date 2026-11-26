// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Vaibhav - https://github.com/thisisforlearn
package com.newpgp.keyboard.ai

/**
 * Diagnostics sink for the AI package.
 *
 * SECURITY CONTRACT: implementations must only emit fixed event codes such as
 * `load.start`, `load.ok`, `rewrite.fallback`, `error.signature`. They must never
 * receive, format, hash or retain typed text, rewritten text, token ids or file
 * contents. No analytics, no network, no plaintext logging.
 */
interface AiLogger {
    fun event(code: String)
    fun error(code: String)
}

/** Production default: emit nothing at all. */
object NoOpAiLogger : AiLogger {
    override fun event(code: String) = Unit
    override fun error(code: String) = Unit
}
