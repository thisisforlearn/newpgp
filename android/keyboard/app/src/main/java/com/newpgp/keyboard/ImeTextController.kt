// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Vaibhav - https://github.com/thisisforlearn
package com.newpgp.keyboard

import com.newpgp.keyboard.autocorrect.AutocorrectEngine
import com.newpgp.keyboard.autocorrect.TextProcessing
import java.util.Locale

/**
 * Android-free editing and session state for the IME.
 *
 * Every multi-step edit is wrapped in a batch and, crucially, *never* calls
 * `finishComposingText()` before `commitText()`: commitText replaces the composing region
 * by contract, so finishing first would leave the typed plaintext behind the armor (or
 * duplicate it when a space/suggestion is committed).
 */
class ImeTextController(
    private val engine: AutocorrectEngine,
    private val autocorrectEnabled: () -> Boolean = { true },
    private val onComposingChanged: (String) -> Unit = {}
) {

    /** A best-effort view of the editor; [text] is what we will actually encrypt. */
    data class FieldSnapshot(
        val text: String,
        val fieldLength: Int,
        val fromSelection: Boolean
    )

    private val composing = StringBuilder()

    /** Bumped on every input-session transition; stale async callbacks check this. */
    var generation: Int = 0
        private set

    /** At-most-once guard for the experimental auto-append inside one input session. */
    var hasAppendedInThisSession: Boolean = false
        private set

    fun composingText(): String = composing.toString()

    fun isCurrentGeneration(value: Int): Boolean = value == generation

    fun startInput(): Int {
        generation++
        hasAppendedInThisSession = false
        resetComposing()
        return generation
    }

    fun finishInput() {
        generation++
        resetComposing()
    }

    fun resetComposing() {
        composing.clear()
        onComposingChanged(composing.toString())
    }

    /** Claims the single auto-append allowed for this input session. */
    fun claimAutoAppend(): Boolean {
        if (hasAppendedInThisSession) return false
        hasAppendedInThisSession = true
        return true
    }

    /** Types a single character into the composing region; false means "commit it raw". */
    fun typeCharacter(ic: TextEditConnection, text: String, shiftActive: Boolean): Boolean {
        val first = text.firstOrNull() ?: return false
        when {
            first.isLetter() && text.length == 1 -> {
                val character = if (shiftActive) first.uppercaseChar() else first.lowercaseChar()
                composing.append(character)
                publishComposing(ic)
                return true
            }
            first == '\'' && composing.isNotEmpty() -> {
                composing.append(first)
                publishComposing(ic)
                return true
            }
            else -> return false
        }
    }

    /**
     * Commits the composing word (optionally corrected) plus [trailing].
     *
     * The separator goes BEFORE the word, never after it, so `Hi.` + `there` + space
     * becomes `Hi. there ` instead of `Hi.there  `.
     */
    fun commitWordAndText(ic: TextEditConnection, trailing: String, capsMode: Int = 0) {
        val word = composing.toString()
        val context = contextBeforeCursor(ic)
        val capitalize = TextProcessing.isSentenceStart(context) || capsMode != 0
        val allowCorrection = autocorrectEnabled() && word.isNotEmpty()
        val replacement = if (allowCorrection) engine.correction(word, capitalize) else null
        // Capitalization is not "correction": it must still apply when the user
        // disabled autocorrect, otherwise the first word of a sentence stays lowercase.
        val finalWord = when {
            replacement != null -> replacement
            capitalize -> TextProcessing.applyCase(word, true)
            else -> word
        }
        val separator = TextProcessing.separatorBefore(context, finalWord)

        ic.beginBatchEdit()
        try {
            ic.commitText(separator + finalWord + trailing, 1)
        } finally {
            ic.endBatchEdit()
        }
        if (replacement != null && !replacement.equals(word, ignoreCase = true)) {
            engine.learn(replacement.lowercase(Locale.ROOT))
        }
        resetComposing()
    }

    /**
     * Replaces a tapped suggestion. Returns true only when the word should be learned:
     * tapping the centre slot (the typed word itself) must not teach the engine a typo.
     */
    fun selectSuggestion(ic: TextEditConnection, suggestion: String): Boolean {
        if (suggestion.isEmpty()) return false
        val typed = composing.toString()
        ic.beginBatchEdit()
        try {
            ic.commitText(suggestion, 1)
        } finally {
            ic.endBatchEdit()
        }
        resetComposing()
        val shouldLearn = autocorrectEnabled() && !suggestion.equals(typed, ignoreCase = true)
        if (shouldLearn) engine.learn(suggestion.lowercase(Locale.ROOT))
        return shouldLearn
    }

    /** True when the backspace consumed a selection or the composing region. */
    fun backspace(ic: TextEditConnection): Boolean {
        val selected = ic.getSelectedText(0)?.toString().orEmpty()
        if (selected.isNotEmpty()) {
            ic.beginBatchEdit()
            try {
                ic.commitText("", 1)
            } finally {
                ic.endBatchEdit()
            }
            resetComposing()
            return true
        }
        if (composing.isEmpty()) {
            ic.deleteSurroundingTextInCodePoints(1, 0)
            return false
        }
        composing.deleteCharAt(composing.length - 1)
        ic.beginBatchEdit()
        try {
            if (composing.isEmpty()) ic.finishComposingText() else publishComposing(ic)
        } finally {
            ic.endBatchEdit()
        }
        onComposingChanged(composing.toString())
        return true
    }

    /** Explicit shield mode: the armor must be the *only* result of this edit. */
    fun replaceComposingWithArmored(ic: TextEditConnection, armored: String) {
        ic.beginBatchEdit()
        try {
            // No finishComposingText(): commitText replaces the composing region itself.
            ic.commitText(armored, 1)
        } finally {
            ic.endBatchEdit()
        }
        resetComposing()
    }

    /** Whole-field shield mode: select the real field range, then replace it with armor. */
    fun replaceAllWithArmored(ic: TextEditConnection, armored: String, fieldLength: Int) {
        ic.beginBatchEdit()
        try {
            if (fieldLength > 0) ic.setSelection(0, fieldLength)
            ic.commitText(armored, 1)
        } finally {
            ic.endBatchEdit()
        }
        resetComposing()
    }

    /**
     * Auto-append replaces the entire field with `original + blank line + armor`, so it
     * works even when Enter was pressed in the middle of the text. Returns the cursor
     * offset (the true end of the user's original text, not `original.length` assumed
     * from a cursor position).
     */
    fun appendArmored(ic: TextEditConnection, snapshot: FieldSnapshot, armored: String): Int {
        val original = snapshot.text.trimEnd()
        val combined = original + "\n\n" + armored.trim().trimEnd() + "\n"
        val cursor = original.length
        ic.beginBatchEdit()
        try {
            if (snapshot.fieldLength > 0) ic.setSelection(0, snapshot.fieldLength)
            ic.commitText(combined, 1)
            ic.setSelection(cursor, cursor)
        } finally {
            ic.endBatchEdit()
        }
        return cursor
    }

    /**
     * Prefer an explicit selection; otherwise read before+after the cursor. Never use
     * `getExtractedText`, which can be null, partial or masked for some editors.
     */
    fun wholeFieldSnapshot(ic: TextEditConnection): FieldSnapshot {
        val before = ic.getTextBeforeCursor(WHOLE_FIELD_LOOKBACK, 0)?.toString().orEmpty()
        val after = ic.getTextAfterCursor(WHOLE_FIELD_LOOKBACK, 0)?.toString().orEmpty()
        val fieldLength = before.length + after.length
        val selected = ic.getSelectedText(0)?.toString()
        return if (!selected.isNullOrEmpty()) {
            FieldSnapshot(selected, fieldLength, fromSelection = true)
        } else {
            FieldSnapshot(before + after, fieldLength, fromSelection = false)
        }
    }

    fun contextBeforeCursor(ic: TextEditConnection): String {
        val raw = ic.getTextBeforeCursor(CONTEXT_LOOKBACK, 0)?.toString().orEmpty()
        return if (raw.length >= composing.length) raw.dropLast(composing.length) else raw
    }

    private fun publishComposing(ic: TextEditConnection) {
        ic.setComposingText(composing.toString(), 1)
        onComposingChanged(composing.toString())
    }

    private companion object {
        const val CONTEXT_LOOKBACK = 160
        const val WHOLE_FIELD_LOOKBACK = 8192
    }
}
