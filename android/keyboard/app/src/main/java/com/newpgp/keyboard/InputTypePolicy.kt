// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Vaibhav - https://github.com/thisisforlearn
package com.newpgp.keyboard

/**
 * Pure-Kotlin interpretation of `EditorInfo.inputType` bit masks.
 *
 * The constants mirror `android.view.inputmethod.EditorInfo`; they are duplicated here
 * so the policy is testable on a plain JVM. The service passes the real bit mask in.
 */
object InputTypePolicy {

    private const val MASK_CLASS = 0x0f
    // Variation lives in the high nibble of the low byte; class lives in the low nibble.
    private const val MASK_VARIATION = 0xf0
    private const val CLASS_NULL = 0x00
    private const val CLASS_TEXT = 0x01
    private const val CLASS_NUMBER = 0x02
    private const val TEXT_VARIATION_PASSWORD = 0x80
    private const val TEXT_VARIATION_VISIBLE_PASSWORD = 0x90
    private const val TEXT_VARIATION_WEB_PASSWORD = 0xe0
    private const val NUMBER_VARIATION_PASSWORD = 0x10
    private const val FLAG_NO_SUGGESTIONS = 0x00080000
    private const val FLAG_MULTI_LINE = 0x00020000

    /** Password fields must never have their contents composed, corrected or extracted. */
    fun isPassword(inputType: Int): Boolean {
        val inputClass = inputType and MASK_CLASS
        val variation = inputType and MASK_VARIATION
        return when (inputClass) {
            CLASS_TEXT -> variation in setOf(
                TEXT_VARIATION_PASSWORD,
                TEXT_VARIATION_VISIBLE_PASSWORD,
                TEXT_VARIATION_WEB_PASSWORD
            )
            CLASS_NUMBER -> variation == NUMBER_VARIATION_PASSWORD
            else -> false
        }
    }

    /** NO_SUGGESTION fields and password fields get no strip, no correction and no learning. */
    fun suppressesSuggestions(inputType: Int): Boolean =
        isPassword(inputType) || inputType and FLAG_NO_SUGGESTIONS != 0

    /** TYPE_NULL editors consume raw key events instead of committed text. */
    fun isNullType(inputType: Int): Boolean = inputType and MASK_CLASS == CLASS_NULL

    fun isMultiLine(inputType: Int): Boolean = inputType and FLAG_MULTI_LINE != 0
}
