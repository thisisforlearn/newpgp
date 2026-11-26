// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Vaibhav - https://github.com/thisisforlearn
package com.newpgp.keyboard

/**
 * The small, Android-free subset of `InputConnection` used by the IME's editing logic.
 *
 * Keeping this boundary free of Android types lets the composing-region and batch-edit
 * flows run in ordinary JVM unit tests with a fake connection. The service supplies an
 * adapter backed by the real `InputConnection`.
 */
interface TextEditConnection {

    /** Commits text; per the InputConnection contract this REPLACES the composing region. */
    fun commitText(text: String, newCursorPosition: Int): Boolean

    fun setComposingText(text: String, newCursorPosition: Int): Boolean

    fun finishComposingText(): Boolean

    fun beginBatchEdit(): Boolean

    fun endBatchEdit(): Boolean

    fun getTextBeforeCursor(maxLength: Int, flags: Int): CharSequence?

    fun getTextAfterCursor(maxLength: Int, flags: Int): CharSequence?

    fun getSelectedText(flags: Int): CharSequence?

    fun deleteSurroundingTextInCodePoints(beforeLength: Int, afterLength: Int): Boolean

    fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean

    fun setSelection(start: Int, end: Int): Boolean
}
