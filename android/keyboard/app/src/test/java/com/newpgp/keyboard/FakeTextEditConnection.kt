// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Vaibhav - https://github.com/thisisforlearn
package com.newpgp.keyboard

/**
 * JVM fake of the InputConnection subset used by [ImeTextController].
 *
 * It models the two contract details the real fixes rely on:
 *  - commitText REPLACES an active composing region (and any active selection);
 *  - setComposingText replaces the previous composing region.
 */
class FakeTextEditConnection : TextEditConnection {

    private val buffer = StringBuilder()
    var cursor: Int = 0
        private set
    var composingStart: Int = -1
        private set
    var composingEnd: Int = -1
        private set
    var selectionStart: Int = -1
        private set
    var selectionEnd: Int = -1
        private set
    var batchDepth: Int = 0
        private set

    fun text(): String = buffer.toString()

    fun setText(value: String, newCursor: Int = value.length) {
        buffer.clear()
        buffer.append(value)
        cursor = newCursor.coerceIn(0, value.length)
        composingStart = -1
        composingEnd = -1
        selectionStart = -1
        selectionEnd = -1
    }

    override fun commitText(text: String, newCursorPosition: Int): Boolean {
        val (start, end) = replacementRange()
        buffer.replace(start, end, text)
        composingStart = -1
        composingEnd = -1
        selectionStart = -1
        selectionEnd = -1
        cursor = if (newCursorPosition > 0) {
            start + text.length + newCursorPosition - 1
        } else {
            start + newCursorPosition
        }.coerceIn(0, buffer.length)
        return true
    }

    override fun setComposingText(text: String, newCursorPosition: Int): Boolean {
        val (start, end) = replacementRange()
        buffer.replace(start, end, text)
        composingStart = start
        composingEnd = start + text.length
        selectionStart = -1
        selectionEnd = -1
        cursor = if (newCursorPosition > 0) {
            start + text.length + newCursorPosition - 1
        } else {
            start + newCursorPosition
        }.coerceIn(0, buffer.length)
        return true
    }

    override fun finishComposingText(): Boolean {
        composingStart = -1
        composingEnd = -1
        return true
    }

    override fun beginBatchEdit(): Boolean {
        batchDepth++
        return true
    }

    override fun endBatchEdit(): Boolean {
        batchDepth = (batchDepth - 1).coerceAtLeast(0)
        return true
    }

    override fun getTextBeforeCursor(maxLength: Int, flags: Int): CharSequence =
        buffer.substring((cursor - maxLength).coerceAtLeast(0), cursor)

    override fun getTextAfterCursor(maxLength: Int, flags: Int): CharSequence =
        buffer.substring(cursor, (cursor + maxLength).coerceAtMost(buffer.length))

    override fun getSelectedText(flags: Int): CharSequence? =
        if (selectionStart >= 0 && selectionEnd > selectionStart) {
            buffer.substring(selectionStart, selectionEnd)
        } else {
            null
        }

    override fun deleteSurroundingTextInCodePoints(beforeLength: Int, afterLength: Int): Boolean {
        val end = (cursor + afterLength).coerceAtMost(buffer.length)
        buffer.delete(end - afterLength.coerceAtMost(end - cursor), end)
        val newCursor = (cursor - beforeLength).coerceAtLeast(0)
        buffer.delete(newCursor, cursor)
        cursor = newCursor
        return true
    }

    override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean =
        deleteSurroundingTextInCodePoints(beforeLength, afterLength)

    override fun setSelection(start: Int, end: Int): Boolean {
        val from = start.coerceIn(0, buffer.length)
        val to = end.coerceIn(0, buffer.length)
        selectionStart = minOf(from, to)
        selectionEnd = maxOf(from, to)
        cursor = to
        return true
    }

    private fun replacementRange(): Pair<Int, Int> = when {
        selectionStart >= 0 && selectionEnd > selectionStart -> selectionStart to selectionEnd
        composingStart >= 0 && composingEnd >= composingStart -> composingStart to composingEnd
        else -> cursor to cursor
    }
}
