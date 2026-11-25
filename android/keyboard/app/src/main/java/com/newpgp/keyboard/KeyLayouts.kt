// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Vaibhav - https://github.com/thisisforlearn
package com.newpgp.keyboard

/** Programmatic key definitions; no XML Keyboard resource is needed. */
enum class ShiftState { OFF, ON, LOCKED }

enum class KeyboardLayer { LETTERS, NUMBERS, SYMBOLS, EMOJI }

enum class KeyType { CHARACTER, SPACE, SHIFT, BACKSPACE, ENTER, LAYOUT_SWITCH, EMOJI, ENCRYPT, SPACER }

data class KeyDef(
    val type: KeyType,
    val label: String? = null,
    val text: String? = null,
    val iconRes: Int? = null,
    val weight: Float = 1f,
    val layer: KeyboardLayer? = null,
    val iconScale: Float = 0.42f
)

data class KeyRow(val keys: List<KeyDef>)

/** Compact, ergonomic QWERTY with numbers, symbols and emoji layers. */
object KeyLayouts {

    fun rows(layer: KeyboardLayer, shiftActive: Boolean): List<KeyRow> = when (layer) {
        KeyboardLayer.LETTERS -> letters(shiftActive)
        KeyboardLayer.NUMBERS -> numbers()
        KeyboardLayer.SYMBOLS -> symbols()
        KeyboardLayer.EMOJI -> emoji()
    }

    private fun letters(shiftActive: Boolean) = listOf(
        KeyRow("qwertyuiop".map(::charKey)),
        KeyRow(listOf(spacer(0.5f)) + "asdfghjkl".map(::charKey) + listOf(spacer(0.5f))),
        KeyRow(listOf(shiftKey(shiftActive)) + "zxcvbnm".map(::charKey) + listOf(backspaceKey())),
        bottomRow("?123", KeyboardLayer.NUMBERS)
    )

    private fun numbers() = listOf(
        KeyRow("1234567890".map(::charKey)),
        KeyRow("-/:;()\$&@\"".map(::charKey)),
        KeyRow(listOf(switchKey("#+=", KeyboardLayer.SYMBOLS)) + "=+*?!'".map(::charKey) +
            listOf(charKey(','), backspaceKey())),
        bottomRow("ABC", KeyboardLayer.LETTERS)
    )

    private fun symbols() = listOf(
        KeyRow("[]{}#%^_\\|".map(::charKey)),
        KeyRow("~`=+/*<>€£".map(::charKey)),
        KeyRow(listOf(switchKey("123", KeyboardLayer.NUMBERS)) + "?!':;()".map(::charKey) +
            listOf(backspaceKey())),
        bottomRow("ABC", KeyboardLayer.LETTERS)
    )

    private fun emoji() = listOf(
        KeyRow(listOf("😀", "😃", "😄", "😁", "😆", "😅", "😂", "🙂", "🙃", "😉").map(::textKey)),
        KeyRow(listOf("😊", "🥰", "😍", "🤩", "😘", "😋", "😎", "🤓", "🧐", "🤔").map(::textKey)),
        KeyRow(listOf("👍", "👎", "👏", "🙌", "🤝", "💪", "🙏", "✌️", "🤞", "👌").map(::textKey)),
        KeyRow(listOf("❤️", "🧡", "💛", "💚", "💙", "💜", "🖤", "✨", "🔒", "🌟").map(::textKey)),
        bottomRow("ABC", KeyboardLayer.LETTERS)
    )

    private fun bottomRow(switchLabel: String, switchLayer: KeyboardLayer) = KeyRow(
        listOf(
            switchKey(switchLabel, switchLayer, weight = 1.4f),
            charKey(','),
            KeyDef(KeyType.SPACE, label = "space", weight = 4.2f),
            charKey('.'),
            encryptKey(),
            emojiKey(),
            enterKey()
        )
    )

    private fun charKey(char: Char) = textKey(char.toString())

    private fun textKey(text: String) = KeyDef(KeyType.CHARACTER, label = text, text = text)

    private fun spacer(weight: Float) = KeyDef(KeyType.SPACER, weight = weight)

    private fun shiftKey(active: Boolean) = KeyDef(
        type = KeyType.SHIFT,
        label = "shift",
        iconRes = R.drawable.ic_shift,
        weight = 1.4f,
        iconScale = 0.46f
    ).let { if (active) it.copy(iconRes = R.drawable.ic_shift_locked) else it }

    private fun backspaceKey() = KeyDef(
        type = KeyType.BACKSPACE,
        label = "backspace",
        iconRes = R.drawable.ic_backspace,
        weight = 1.4f
    )

    private fun enterKey() = KeyDef(
        type = KeyType.ENTER,
        label = "enter",
        iconRes = R.drawable.ic_enter,
        weight = 1.5f
    )

    private fun encryptKey() = KeyDef(
        type = KeyType.ENCRYPT,
        label = "encrypt",
        iconRes = R.drawable.ic_shield_lock,
        weight = 1.2f,
        iconScale = 0.48f
    )

    private fun emojiKey() = KeyDef(
        type = KeyType.EMOJI,
        label = "emoji",
        iconRes = R.drawable.ic_emoji,
        weight = 1f
    )

    private fun switchKey(label: String, layer: KeyboardLayer, weight: Float = 1.4f) = KeyDef(
        type = KeyType.LAYOUT_SWITCH,
        label = label,
        layer = layer,
        weight = weight
    )
}
