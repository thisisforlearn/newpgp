// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Vaibhav - https://github.com/thisisforlearn
package com.newpgp.keyboard.ai

/**
 * Writing styles offered by the offline tone engine.
 *
 * `GRAMMAR_ONLY` is handled entirely by [GrammarRules] in pure Kotlin and is available
 * instantly, even before any model file is loaded. The other tones use the local
 * seq2seq model when it is ready and fall back to conservative rule-based editing
 * while the model loads (see [ToneEngine]).
 *
 * UI labels live in `res/values/strings_ai.xml`:
 * GRAMMAR_ONLY -> `ai_fix_grammar`, PROFESSIONAL -> `ai_make_professional`,
 * FRIENDLY -> `ai_make_friendly`, CONCISE -> `ai_make_concise`.
 */
enum class Tone {

    /** Correct articles, capitalization, spacing and common typos. No model needed. */
    GRAMMAR_ONLY,

    /** Neutral, clear business wording. Model path (when available). */
    PROFESSIONAL,

    /** Warmer, more conversational wording. Model path (when available). */
    FRIENDLY,

    /** Shorter wording with filler removed. Model path (when available). */
    CONCISE;

    /**
     * Small style hint prepended before encoding for model-backed tones.
     *
     * The bundled model was fine-tuned for grammar correction, not style transfer, so
     * these prefixes are intentionally short. They are a hook for a future fine-tune;
     * they do not claim to guarantee a particular style today.
     */
    val modelPrompt: String
        get() = when (this) {
            GRAMMAR_ONLY -> "grammar: "
            PROFESSIONAL -> "professional: "
            FRIENDLY -> "friendly: "
            CONCISE -> "concise: "
        }
}
