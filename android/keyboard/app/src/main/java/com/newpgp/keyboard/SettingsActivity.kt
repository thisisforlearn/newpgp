// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Vaibhav - https://github.com/thisisforlearn
package com.newpgp.keyboard

import android.content.DialogInterface
import android.os.Bundle
import android.widget.CheckBox
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.newpgp.keyboard.databinding.ActivitySettingsBinding
import com.newpgp.keyboard.settings.SettingsStore

/** Settings screen reachable from system Settings > Languages & input > NewPGP Keyboard. */
class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var settings: SettingsStore
    private var applyingProgrammatically = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        settings = SettingsStore(this)

        setSwitchState(binding.autocorrectSwitch, settings.autocorrectEnabled)
        binding.autocorrectSwitch.setOnCheckedChangeListener { _, checked ->
            if (applyingProgrammatically) return@setOnCheckedChangeListener
            settings.autocorrectEnabled = checked
        }

        setSwitchState(binding.autoAppendSwitch, settings.autoAppendEnabled)
        binding.autoAppendSwitch.setOnCheckedChangeListener { _, checked ->
            if (applyingProgrammatically) return@setOnCheckedChangeListener
            if (!checked) {
                settings.autoAppendEnabled = false
                return@setOnCheckedChangeListener
            }
            // Revert immediately: the dangerous option requires an explicit, acknowledged opt-in.
            setSwitchState(binding.autoAppendSwitch, false)
            showDangerousModeConfirmation {
                setSwitchState(binding.autoAppendSwitch, true)
                settings.autoAppendEnabled = true
            }
        }
    }

    private fun setSwitchState(switch: com.google.android.material.materialswitch.MaterialSwitch, value: Boolean) {
        applyingProgrammatically = true
        switch.isChecked = value
        applyingProgrammatically = false
    }

    private fun showDangerousModeConfirmation(onConfirmed: () -> Unit) {
        val view = layoutInflater.inflate(R.layout.dialog_danger_confirm, null)
        val acknowledgement = view.findViewById<CheckBox>(R.id.acknowledgement)
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.danger_dialog_title)
            .setMessage(R.string.danger_dialog_message)
            .setView(view)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.danger_dialog_enable) { _, _ -> onConfirmed() }
            .show()

        val positive = dialog.getButton(DialogInterface.BUTTON_POSITIVE)
        positive.isEnabled = false
        acknowledgement.setOnCheckedChangeListener { _, checked -> positive.isEnabled = checked }
    }
}
