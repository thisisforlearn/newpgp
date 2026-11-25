// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Vaibhav - https://github.com/thisisforlearn
package com.newpgp.keyboard.settings

import android.content.Context

/**
 * Stores only two booleans in app-private SharedPreferences.
 * No typed text, composing region, or keystroke is ever persisted.
 */
class SettingsStore(context: Context) {

    private val preferences = context.applicationContext
        .getSharedPreferences("newpgp_keyboard_settings", Context.MODE_PRIVATE)

    var autocorrectEnabled: Boolean
        get() = preferences.getBoolean(KEY_AUTOCORRECT, true)
        set(value) = preferences.edit().putBoolean(KEY_AUTOCORRECT, value).apply()

    /** Experimental and dangerous: OFF unless the user explicitly opts in. */
    var autoAppendEnabled: Boolean
        get() = preferences.getBoolean(KEY_AUTO_APPEND, false)
        set(value) = preferences.edit().putBoolean(KEY_AUTO_APPEND, value).apply()

    private companion object {
        const val KEY_AUTOCORRECT = "autocorrect_enabled"
        const val KEY_AUTO_APPEND = "auto_append_encrypted_block"
    }
}
