// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Vaibhav - https://github.com/thisisforlearn
package com.newpgp.keyboard.settings

import android.content.Context
import com.newpgp.keyboard.autocorrect.LearnedWords

/**
 * Persistence hook for learned-word counts only. Stores lowercase words -> counts,
 * never sentences or keystroke sequences. Nothing is uploaded or backed up.
 */
class SharedPreferencesLearnedWordsStore(context: Context) : LearnedWords.Persistence {

    private val preferences = context.applicationContext
        .getSharedPreferences("newpgp_keyboard_learned", Context.MODE_PRIVATE)

    override fun load(): Map<String, Int> = preferences.all
        .mapNotNull { (key, value) -> if (value is Int) key to value else null }
        .toMap()

    override fun save(words: Map<String, Int>) {
        val editor = preferences.edit().clear()
        words.forEach { (word, count) -> editor.putInt(word, count) }
        editor.apply()
    }
}
