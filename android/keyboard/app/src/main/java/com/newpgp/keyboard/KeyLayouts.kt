// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Vaibhav - https://github.com/thisisforlearn
package com.newpgp.keyboard

/** Programmatic key definitions; no XML Keyboard resource is needed. */
enum class ShiftState { OFF, ON, LOCKED }

enum class KeyboardLayer { LETTERS, NUMBERS, SYMBOLS, EMOJI }

/**
 * Actions the IME service receives through [KeyboardView.OnKeyListener].
 *
 * BRAND is decorative and is never dispatched by the view. CLIPBOARD, AI_TONE, MIC,
 * GIF, MORE and SHIELD are new toolbar events; the service agent owns their handlers.
 */
enum class KeyType {
    CHARACTER, SPACE, SHIFT, BACKSPACE, ENTER, LAYOUT_SWITCH, EMOJI, ENCRYPT,
    SHIELD, AI_TONE, CLIPBOARD, MIC, GIF, MORE, BRAND, SPACER
}

data class KeyDef(
    val type: KeyType,
    val label: String? = null,
    val text: String? = null,
    val iconRes: Int? = null,
    val weight: Float = 1f,
    val layer: KeyboardLayer? = null,
    val iconScale: Float = 0.42f,
    /** Accessibility label; resolved from resources so the strip stays localizable. */
    val descriptionRes: Int? = null,
    /** Long-press help bubble, e.g. the shield action. */
    val tooltipRes: Int? = null
)

data class KeyRow(
    val keys: List<KeyDef>,
    /** Toolbar rows are shorter, use a tighter horizontal gap and wider touch targets. */
    val isToolbar: Boolean = false
)

/** Compact, ergonomic QWERTY with a SwiftKey-style toolbar and numbers/symbols/emoji layers. */
object KeyLayouts {

    /** Row 0 of the keyboard area; the view keeps it visible on every layer. */
    fun toolbarRow() = KeyRow(
        listOf(
            KeyDef(
                type = KeyType.BRAND,
                label = "NewPGP",
                iconRes = R.drawable.ic_newpgp_brand,
                iconScale = 0.66f,
                descriptionRes = R.string.toolbar_brand
            ),
            toolbarKey(KeyType.CLIPBOARD, R.drawable.ic_clipboard, R.string.toolbar_clipboard),
            toolbarKey(KeyType.AI_TONE, R.drawable.ic_sparkle, R.string.toolbar_ai_tone),
            toolbarKey(KeyType.MIC, R.drawable.ic_mic, R.string.toolbar_mic),
            toolbarKey(KeyType.EMOJI, R.drawable.ic_emoji, R.string.toolbar_emoji),
            toolbarKey(KeyType.GIF, R.drawable.ic_gif, R.string.toolbar_gif),
            toolbarKey(KeyType.MORE, R.drawable.ic_more, R.string.toolbar_more),
            KeyDef(
                type = KeyType.SHIELD,
                label = "shield",
                iconRes = R.drawable.ic_shield_lock,
                weight = 1.2f,
                iconScale = 0.5f,
                descriptionRes = R.string.toolbar_shield,
                tooltipRes = R.string.toolbar_shield_tooltip
            )
        ),
        isToolbar = true
    )

    fun rows(layer: KeyboardLayer, shiftActive: Boolean): List<KeyRow> = when (layer) {
        KeyboardLayer.LETTERS -> letters(shiftActive)
        KeyboardLayer.NUMBERS -> numbers()
        KeyboardLayer.SYMBOLS -> symbols()
        KeyboardLayer.EMOJI -> emoji()
    }

    private fun toolbarKey(type: KeyType, icon: Int, description: Int) = KeyDef(
        type = type,
        iconRes = icon,
        iconScale = 0.54f,
        descriptionRes = description
    )

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
        iconScale = 0.46f,
        descriptionRes = R.string.key_shift
    ).let { if (active) it.copy(iconRes = R.drawable.ic_shift_locked) else it }

    private fun backspaceKey() = KeyDef(
        type = KeyType.BACKSPACE,
        label = "backspace",
        iconRes = R.drawable.ic_backspace,
        weight = 1.4f,
        descriptionRes = R.string.key_backspace
    )

    private fun enterKey() = KeyDef(
        type = KeyType.ENTER,
        label = "enter",
        iconRes = R.drawable.ic_enter,
        weight = 1.5f,
        descriptionRes = R.string.key_enter
    )

    private fun encryptKey() = KeyDef(
        type = KeyType.ENCRYPT,
        label = "encrypt",
        iconRes = R.drawable.ic_shield_lock,
        weight = 1.2f,
        iconScale = 0.48f,
        descriptionRes = R.string.key_encrypt,
        tooltipRes = R.string.toolbar_shield_tooltip
    )

    private fun emojiKey() = KeyDef(
        type = KeyType.EMOJI,
        label = "emoji",
        iconRes = R.drawable.ic_emoji,
        weight = 1f,
        descriptionRes = R.string.key_emoji
    )

    private fun switchKey(label: String, layer: KeyboardLayer, weight: Float = 1.4f) = KeyDef(
        type = KeyType.LAYOUT_SWITCH,
        label = label,
        layer = layer,
        weight = weight
    )
}
