// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Vaibhav - https://github.com/thisisforlearn
package com.newpgp.keyboard

import android.inputmethodservice.InputMethodService
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection
import android.widget.Toast
import com.newpgp.keyboard.autocorrect.AutocorrectEngine
import com.newpgp.keyboard.autocorrect.LearnedWords
import com.newpgp.keyboard.autocorrect.TextProcessing
import com.newpgp.keyboard.databinding.InputViewBinding
import com.newpgp.keyboard.settings.SettingsStore
import com.newpgp.keyboard.settings.SharedPreferencesLearnedWordsStore
import java.util.Locale

/**
 * NewPGP soft keyboard.
 *
 * PRIVACY: this IME never logs keystrokes, never adds analytics, never opens a network
 * socket, and never writes typed text to disk. The current composing word lives only in
 * [composing] for the duration of the input session. The only text egress is the declared
 * [PgpBridge] (currently a deliberately failing native stub). Settings and learned-word
 * counts are the sole app-private persisted data.
 */
class NewPgpImeService : InputMethodService(),
    KeyboardView.OnKeyListener,
    SuggestionStripView.OnSuggestionListener {

    private lateinit var binding: InputViewBinding
    private lateinit var engine: AutocorrectEngine
    private lateinit var settings: SettingsStore
    private lateinit var encryption: EncryptionController

    private var shiftState = ShiftState.OFF
    private var layer = KeyboardLayer.LETTERS
    private val composing = StringBuilder()
    private var hideStatus: Runnable? = null

    override fun onCreate() {
        super.onCreate()
        settings = SettingsStore(this)
        engine = AutocorrectEngine(
            learnedWords = LearnedWords(SharedPreferencesLearnedWordsStore(this))
        )
        encryption = EncryptionController(PgpBridgeProvider.current)
    }

    override fun onCreateInputView(): View {
        binding = InputViewBinding.inflate(layoutInflater)
        binding.keyboardView.onKeyListener = this
        binding.suggestionStrip.onSuggestionListener = this
        binding.keyboardView.setLayer(layer)
        binding.keyboardView.setShiftState(shiftState)
        updateSuggestions()
        return binding.root
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        if (!::binding.isInitialized) return
        resetComposing()
        setShift(ShiftState.OFF)
        val inputClass = info?.inputType?.and(EditorInfo.TYPE_MASK_CLASS)
        setLayer(
            if (inputClass == EditorInfo.TYPE_CLASS_NUMBER) KeyboardLayer.NUMBERS else KeyboardLayer.LETTERS
        )
        binding.statusText.visibility = View.GONE
        updateSuggestions()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        // Never leave an unfinished composing region behind in the target app.
        currentInputConnection?.finishComposingText()
        resetComposing()
        super.onFinishInputView(finishingInput)
    }

    override fun onFinishInput() {
        currentInputConnection?.finishComposingText()
        resetComposing()
        super.onFinishInput()
    }

    override fun onKey(key: KeyDef) {
        when (key.type) {
            KeyType.CHARACTER -> handleCharacter(key.text ?: key.label ?: "")
            KeyType.SPACE -> currentInputConnection?.let { commitWordAndText(it, " ") }
            KeyType.SHIFT -> cycleShift()
            KeyType.BACKSPACE -> handleBackspace()
            KeyType.ENTER -> handleReturnKey()
            KeyType.LAYOUT_SWITCH -> setLayer(key.layer ?: KeyboardLayer.LETTERS)
            KeyType.EMOJI -> setLayer(KeyboardLayer.EMOJI)
            KeyType.ENCRYPT -> handleEncrypt()
            KeyType.SPACER -> Unit
        }
    }

    override fun onSuggestionSelected(suggestion: String) {
        val ic = currentInputConnection ?: return
        if (suggestion.isEmpty()) return
        ic.finishComposingText()
        ic.commitText(suggestion, 1)
        engine.learn(suggestion.lowercase(Locale.ROOT))
        resetComposing()
    }

    private fun handleCharacter(text: String) {
        val ic = currentInputConnection ?: return
        val first = text.firstOrNull() ?: return
        when {
            first.isLetter() && text.length == 1 -> {
                val character = if (shiftState != ShiftState.OFF) {
                    text.uppercase(Locale.ROOT)
                } else {
                    text.lowercase(Locale.ROOT)
                }
                composing.append(character)
                ic.setComposingText(composing.toString(), 1)
                if (shiftState == ShiftState.ON) setShift(ShiftState.OFF)
                updateSuggestions()
            }
            first == '\'' && composing.isNotEmpty() -> {
                composing.append(first)
                ic.setComposingText(composing.toString(), 1)
                updateSuggestions()
            }
            else -> commitWordAndText(ic, text)
        }
    }

    /** Commits the composing word (optionally corrected) plus the trailing character. */
    private fun commitWordAndText(ic: InputConnection, trailing: String) {
        val word = composing.toString()
        val context = contextBeforeCursor()
        val replacement = if (settings.autocorrectEnabled && word.isNotEmpty()) {
            engine.correction(word, TextProcessing.isSentenceStart(context))
        } else {
            null
        }
        val finalWord = replacement ?: word
        val separator = TextProcessing.separatorBefore(context, trailing)
        ic.finishComposingText()
        ic.commitText(finalWord + separator + trailing, 1)
        if (replacement != null && !replacement.equals(word, ignoreCase = true)) {
            engine.learn(replacement.lowercase(Locale.ROOT))
        }
        resetComposing()
    }

    private fun handleBackspace() {
        val ic = currentInputConnection ?: return
        if (composing.isEmpty()) {
            ic.deleteSurroundingText(1, 0)
            return
        }
        composing.deleteCharAt(composing.length - 1)
        if (composing.isEmpty()) {
            ic.finishComposingText()
        } else {
            ic.setComposingText(composing.toString(), 1)
        }
        updateSuggestions()
    }

    /** Enter: finish the word, optionally auto-append, then honour the editor action. */
    private fun handleReturnKey(): Boolean {
        val ic = currentInputConnection ?: return false
        if (composing.isNotEmpty()) commitWordAndText(ic, "")
        if (settings.autoAppendEnabled) appendEncryptedBlock(ic)

        val action = currentInputEditorInfo?.imeOptions?.and(EditorInfo.IME_MASK_ACTION)
            ?: EditorInfo.IME_ACTION_UNSPECIFIED
        val actionHandled = when (action) {
            EditorInfo.IME_ACTION_NONE, EditorInfo.IME_ACTION_UNSPECIFIED -> false
            else -> ic.performEditorAction(action)
        }
        if (!actionHandled) ic.commitText("\n", 1)
        return true
    }

    /**
     * Explicit, recommended mode: replace the current composing text (or the whole field
     * when no word is being composed) with an armored block and show visible confirmation.
     */
    private fun handleEncrypt() {
        val ic = currentInputConnection ?: return
        val plaintext = composing.toString().ifEmpty { extractedText(ic) }
        if (plaintext.isBlank()) {
            showStatus(getString(R.string.status_nothing_to_encrypt))
            return
        }
        encryption.encrypt(plaintext)
            .onSuccess { armored ->
                ic.beginBatchEdit()
                ic.finishComposingText()
                ic.commitText(armored, 1)
                ic.endBatchEdit()
                resetComposing()
                showStatus(getString(R.string.status_encrypted, plaintext.length))
                Toast.makeText(this, R.string.toast_encrypted, Toast.LENGTH_SHORT).show()
            }
            .onFailure { showFailure(it) }
    }

    /** Experimental mode, default OFF: append armor after the whole field on Enter. */
    private fun appendEncryptedBlock(ic: InputConnection) {
        val original = extractedText(ic)
        if (original.isBlank()) {
            showStatus(getString(R.string.status_nothing_to_encrypt))
            return
        }
        encryption.encrypt(original)
            .onSuccess { armored ->
                ic.beginBatchEdit()
                ic.commitText(encryption.buildAutoAppendText(original, armored), 1)
                // Leave the cursor at the end of the user's original text.
                ic.setSelection(original.length, original.length)
                ic.endBatchEdit()
                showStatus(getString(R.string.status_auto_appended))
            }
            .onFailure { showFailure(it) }
    }

    private fun extractedText(ic: InputConnection): String =
        ic.getExtractedText(ExtractedTextRequest(), 0)?.text?.toString().orEmpty()

    private fun contextBeforeCursor(): String {
        val ic = currentInputConnection ?: return ""
        val raw = ic.getTextBeforeCursor(CONTEXT_LOOKBACK, 0)?.toString().orEmpty()
        return if (raw.length >= composing.length) raw.dropLast(composing.length) else raw
    }

    private fun updateSuggestions() {
        if (!::binding.isInitialized) return
        val typed = composing.toString()
        if (typed.isEmpty()) {
            binding.suggestionStrip.suggestions = emptyList()
            return
        }
        val alternatives = if (settings.autocorrectEnabled) {
            engine.suggestions(typed, TextProcessing.isSentenceStart(contextBeforeCursor()), SUGGESTION_COUNT)
                .filterNot { it.equals(typed, ignoreCase = true) }
        } else {
            emptyList()
        }
        binding.suggestionStrip.suggestions = listOf(
            alternatives.getOrNull(0).orEmpty(),
            typed,
            alternatives.getOrNull(1).orEmpty()
        )
    }

    private fun showStatus(message: String) {
        if (!::binding.isInitialized) {
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
            return
        }
        hideStatus?.let { binding.statusText.removeCallbacks(it) }
        binding.statusText.text = message
        binding.statusText.visibility = View.VISIBLE
        val runnable = Runnable { binding.statusText.visibility = View.GONE }
        hideStatus = runnable
        binding.statusText.postDelayed(runnable, STATUS_DURATION_MS)
    }

    private fun showFailure(error: Throwable) {
        showStatus(getString(R.string.status_encryption_unavailable, encryption.describeFailure(error)))
    }

    private fun cycleShift() {
        val next = when (shiftState) {
            ShiftState.OFF -> ShiftState.ON
            ShiftState.ON -> ShiftState.LOCKED
            ShiftState.LOCKED -> ShiftState.OFF
        }
        setShift(next)
    }

    private fun setShift(value: ShiftState) {
        shiftState = value
        if (::binding.isInitialized) binding.keyboardView.setShiftState(value)
    }

    private fun setLayer(value: KeyboardLayer) {
        layer = value
        if (::binding.isInitialized) binding.keyboardView.setLayer(value)
    }

    private fun resetComposing() {
        composing.clear()
        if (::binding.isInitialized) updateSuggestions()
    }

    private companion object {
        const val CONTEXT_LOOKBACK = 160
        const val SUGGESTION_COUNT = 3
        const val STATUS_DURATION_MS = 4000L
    }
}
