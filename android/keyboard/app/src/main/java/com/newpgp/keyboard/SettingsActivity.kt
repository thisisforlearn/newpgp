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
    private var dangerDialogRequested = false
    private var acknowledgementChecked = false

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
            showDangerousModeConfirmation { confirmAutoAppend() }
        }

        // Rotation must not silently lose (or silently accept) the warning dialog.
        savedInstanceState?.let { state ->
            if (state.getBoolean(KEY_DANGER_DIALOG, false)) {
                acknowledgementChecked = state.getBoolean(KEY_ACKNOWLEDGED, false)
                restoreDangerDialogConfirmation(acknowledgementChecked)
            }
        }
    }

    private fun confirmAutoAppend() {
        setSwitchState(binding.autoAppendSwitch, true)
        settings.autoAppendEnabled = true
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(KEY_DANGER_DIALOG, dangerDialogRequested)
        outState.putBoolean(KEY_ACKNOWLEDGED, acknowledgementChecked)
    }

    private fun setSwitchState(switch: com.google.android.material.materialswitch.MaterialSwitch, value: Boolean) {
        applyingProgrammatically = true
        switch.isChecked = value
        applyingProgrammatically = false
    }

    private fun restoreDangerDialogConfirmation(initiallyAcknowledged: Boolean) {
        dangerDialogRequested = true
        showDangerDialog(
            onConfirmed = {
                dangerDialogRequested = false
                confirmAutoAppend()
            },
            onCancelled = { dangerDialogRequested = false },
            initiallyAcknowledged = initiallyAcknowledged
        )
    }

    private fun showDangerousModeConfirmation(onConfirmed: () -> Unit) {
        dangerDialogRequested = true
        showDangerDialog(
            onConfirmed = {
                dangerDialogRequested = false
                onConfirmed()
            },
            onCancelled = { dangerDialogRequested = false },
            initiallyAcknowledged = false
        )
    }

    private fun showDangerDialog(
        onConfirmed: () -> Unit,
        onCancelled: () -> Unit,
        initiallyAcknowledged: Boolean
    ) {
        acknowledgementChecked = initiallyAcknowledged
        val view = layoutInflater.inflate(R.layout.dialog_danger_confirm, null)
        val acknowledgement = view.findViewById<CheckBox>(R.id.acknowledgement)
        acknowledgement.isChecked = initiallyAcknowledged
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.danger_dialog_title)
            .setMessage(R.string.danger_dialog_message)
            .setView(view)
            .setNegativeButton(android.R.string.cancel) { _, _ -> onCancelled() }
            .setPositiveButton(R.string.danger_dialog_enable) { _, _ -> onConfirmed() }
            .show()

        val positive = dialog.getButton(DialogInterface.BUTTON_POSITIVE)
        positive.isEnabled = initiallyAcknowledged
        acknowledgement.setOnCheckedChangeListener { _, checked ->
            acknowledgementChecked = checked
            positive.isEnabled = checked
        }
    }

    private companion object {
        const val KEY_DANGER_DIALOG = "newpgp_danger_dialog_visible"
        const val KEY_ACKNOWLEDGED = "newpgp_danger_dialog_acknowledged"
    }
}
