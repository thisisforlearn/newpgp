// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Vaibhav - https://github.com/thisisforlearn
package com.newpgp.keyboard

import android.os.Build
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.widget.Toast
import com.newpgp.keyboard.autocorrect.AutocorrectEngine
import com.newpgp.keyboard.autocorrect.LearnedWords
import com.newpgp.keyboard.autocorrect.TextProcessing
import com.newpgp.keyboard.databinding.InputViewBinding
import com.newpgp.keyboard.settings.SettingsStore
import com.newpgp.keyboard.settings.SharedPreferencesLearnedWordsStore

/**
 * NewPGP soft keyboard.
 *
 * PRIVACY: this IME never logs keystrokes, never adds analytics, never opens a network
 * socket, and never writes typed text to disk. The current composing word lives only in
 * [ImeTextController] for the duration of the input session. The only text egress is the
 * declared [PgpBridge] (currently a deliberately failing native stub). Settings and
 * learned-word counts are the sole app-private persisted data.
 */
class NewPgpImeService : InputMethodService(),
    KeyboardView.OnKeyListener,
    SuggestionStripView.OnSuggestionListener {

    private lateinit var binding: InputViewBinding
    private lateinit var engine: AutocorrectEngine
    private lateinit var settings: SettingsStore
    private lateinit var encryption: EncryptionController
    private lateinit var controller: ImeTextController

    private var shiftState = ShiftState.OFF
    private var layer = KeyboardLayer.LETTERS
    private var activeInfo: EditorInfo? = null
    private var suppressAutocorrect = false
    private var allowWholeFieldEncryption = true
    private var nullInputType = false
    private var multiline = false
    private var hideStatus: Runnable? = null

    override fun onCreate() {
        super.onCreate()
        settings = SettingsStore(this)
        engine = AutocorrectEngine(
            learnedWords = LearnedWords(SharedPreferencesLearnedWordsStore(this))
        )
        encryption = EncryptionController(PgpBridgeProvider.current)
        controller = ImeTextController(
            engine = engine,
            autocorrectEnabled = { settings.autocorrectEnabled && !suppressAutocorrect },
            onComposingChanged = { updateSuggestions() }
        )
    }

    override fun onCreateInputView(): View {
        binding = InputViewBinding.inflate(layoutInflater)
        binding.keyboardView.onKeyListener = this
        binding.suggestionStrip.onSuggestionListener = this
        binding.keyboardView.setLayer(layer)
        binding.keyboardView.setShiftState(shiftState)
        applyEditorInfo(activeInfo)
        updatePersistentWarning()
        updateSuggestions()
        return binding.root
    }

    override fun onStartInput(info: EditorInfo?, restarting: Boolean) {
        super.onStartInput(info, restarting)
        activeInfo = info ?: currentInputEditorInfo
        applyEditorInfo(activeInfo)
        controller.startInput()
        setShift(ShiftState.OFF)
        updateSuggestions()
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        if (!::binding.isInitialized) return
        activeInfo = info ?: currentInputEditorInfo
        applyEditorInfo(activeInfo)
        controller.resetComposing()
        setShift(ShiftState.OFF)
        val inputClass = activeInfo?.inputType?.and(EditorInfo.TYPE_MASK_CLASS)
        setLayer(
            if (inputClass == EditorInfo.TYPE_CLASS_NUMBER) KeyboardLayer.NUMBERS else KeyboardLayer.LETTERS
        )
        updatePersistentWarning()
        updateSuggestions()
    }

    /**
     * If the editor (or the user) moves the cursor outside our composing region, the
     * region is stale: finish it so no invisible composing state can duplicate text.
     */
    override fun onUpdateSelection(
        oldSelStart: Int,
        oldSelEnd: Int,
        newSelStart: Int,
        newSelEnd: Int,
        candidatesStart: Int,
        candidatesEnd: Int
    ) {
        super.onUpdateSelection(
            oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd
        )
        if (controller.composingText().isEmpty()) return
        val rangeIsValid = candidatesStart >= 0 && candidatesEnd > candidatesStart
        val movedOutside = if (rangeIsValid) {
            newSelStart < candidatesStart || newSelEnd > candidatesEnd
        } else {
            true
        }
        if (movedOutside) {
            currentInputConnection?.finishComposingText()
            controller.resetComposing()
        }
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        // Never leave an unfinished composing region behind in the target app.
        currentInputConnection?.finishComposingText()
        controller.resetComposing()
        super.onFinishInputView(finishingInput)
    }

    override fun onFinishInput() {
        currentInputConnection?.finishComposingText()
        controller.finishInput()
        engine.flushLearnedWords()
        super.onFinishInput()
    }

    override fun onDestroy() {
        if (::binding.isInitialized) {
            hideStatus?.let { binding.statusText.removeCallbacks(it) }
            hideStatus = null
        }
        engine.flushLearnedWords()
        super.onDestroy()
    }

    override fun onKey(key: KeyDef): Boolean = when (key.type) {
        KeyType.CHARACTER -> handleCharacter(key.text ?: key.label ?: "")
        KeyType.SPACE -> handleSpace()
        KeyType.SHIFT -> {
            cycleShift()
            true
        }
        KeyType.BACKSPACE -> handleBackspace()
        KeyType.ENTER -> handleReturnKey()
        KeyType.LAYOUT_SWITCH -> {
            setLayer(key.layer ?: KeyboardLayer.LETTERS)
            true
        }
        KeyType.EMOJI -> {
            setLayer(KeyboardLayer.EMOJI)
            true
        }
        KeyType.ENCRYPT -> handleEncrypt()
        KeyType.SPACER -> false
    }

    override fun onSuggestionSelected(suggestion: String) {
        val ic = connection() ?: return
        controller.selectSuggestion(ic, suggestion)
    }

    private fun handleCharacter(text: String): Boolean {
        val rawIc = currentInputConnection ?: return false
        if (nullInputType) {
            val keyCode = keyCodeFor(text)
            if (keyCode == KeyEvent.KEYCODE_UNKNOWN) rawIc.commitText(text, 1) else sendKey(rawIc, keyCode)
            return true
        }
        val ic = InputConnectionAdapter(rawIc)
        if (suppressAutocorrect) {
            ic.beginBatchEdit()
            try {
                ic.commitText(text, 1)
            } finally {
                ic.endBatchEdit()
            }
            return true
        }
        val consumed = controller.typeCharacter(ic, text, shiftState != ShiftState.OFF)
        if (consumed) {
            if (shiftState == ShiftState.ON) setShift(ShiftState.OFF)
        } else {
            controller.commitWordAndText(ic, text, capsMode(rawIc))
        }
        return true
    }

    private fun handleSpace(): Boolean {
        val rawIc = currentInputConnection ?: return false
        if (nullInputType) {
            sendKey(rawIc, KeyEvent.KEYCODE_SPACE)
            return true
        }
        val ic = InputConnectionAdapter(rawIc)
        if (suppressAutocorrect) {
            ic.commitText(" ", 1)
        } else {
            controller.commitWordAndText(ic, " ", capsMode(rawIc))
        }
        return true
    }

    private fun handleBackspace(): Boolean {
        val rawIc = currentInputConnection ?: return false
        if (nullInputType) {
            sendKey(rawIc, KeyEvent.KEYCODE_DEL)
            return true
        }
        controller.backspace(InputConnectionAdapter(rawIc))
        return true
    }

    /** Enter: finish the word, optionally auto-append once, then honour the editor action. */
    private fun handleReturnKey(): Boolean {
        val rawIc = currentInputConnection ?: return false
        val ic = InputConnectionAdapter(rawIc)
        if (controller.composingText().isNotEmpty()) {
            controller.commitWordAndText(ic, "", capsMode(rawIc))
        }

        val info = activeInfo
        val action = info?.actionId?.takeIf { it != EditorInfo.IME_ACTION_UNSPECIFIED }
            ?: info?.imeOptions?.and(EditorInfo.IME_MASK_ACTION)
            ?: EditorInfo.IME_ACTION_UNSPECIFIED

        if (settings.autoAppendEnabled && allowWholeFieldEncryption && controller.claimAutoAppend()) {
            ic.takeSnapshot()
            val snapshot = controller.wholeFieldSnapshot(ic)
            if (snapshot.text.isBlank()) {
                showStatus(getString(R.string.status_nothing_to_encrypt))
            } else {
                val generation = controller.generation
                encryption.encrypt(snapshot.text)
                    .onSuccess { armored ->
                        if (!stillCurrent(generation, rawIc)) return@onSuccess
                        controller.appendArmored(ic, snapshot, armored)
                        showStatus(getString(R.string.status_auto_appended))
                        Toast.makeText(this, R.string.status_auto_appended, Toast.LENGTH_LONG).show()
                    }
                    .onFailure { error ->
                        if (stillCurrent(generation, rawIc)) showFailure(error)
                    }
            }
        }

        if (action != EditorInfo.IME_ACTION_NONE &&
            action != EditorInfo.IME_ACTION_UNSPECIFIED &&
            rawIc.performEditorAction(action)
        ) {
            return true
        }
        if (nullInputType || (info != null && InputTypePolicy.isMultiLine(info.inputType))) {
            sendKey(rawIc, KeyEvent.KEYCODE_ENTER)
            return true
        }
        rawIc.performEditorAction(EditorInfo.IME_ACTION_DONE)
        requestHideSelf(0)
        return true
    }

    /**
     * Explicit shield mode. Composing text is replaced in place; with no composing word
     * the whole field is selected and replaced, so plaintext is never left behind.
     */
    private fun handleEncrypt(): Boolean {
        val rawIc = currentInputConnection ?: return false
        val ic = InputConnectionAdapter(rawIc)
        ic.takeSnapshot()
        val typed = controller.composingText()
        if (typed.isEmpty() && !allowWholeFieldEncryption) {
            showStatus(getString(R.string.status_encryption_unavailable, "disabled for this field"))
            return true
        }
        val snapshot = if (typed.isEmpty()) controller.wholeFieldSnapshot(ic) else null
        val plaintext = snapshot?.text ?: typed
        if (plaintext.isBlank()) {
            showStatus(getString(R.string.status_nothing_to_encrypt))
            return true
        }

        val generation = controller.generation
        encryption.encrypt(plaintext)
            .onSuccess { armored ->
                if (!stillCurrent(generation, rawIc)) return@onSuccess
                if (snapshot != null) {
                    controller.replaceAllWithArmored(ic, armored, snapshot.fieldLength)
                } else {
                    controller.replaceComposingWithArmored(ic, armored)
                }
                showStatus(getString(R.string.status_encrypted, plaintext.length))
                Toast.makeText(this, R.string.toast_encrypted, Toast.LENGTH_SHORT).show()
            }
            .onFailure { error ->
                if (stillCurrent(generation, rawIc)) showFailure(error)
            }
        return true
    }

    private fun stillCurrent(generation: Int, captured: InputConnection): Boolean =
        controller.isCurrentGeneration(generation) && currentInputConnection === captured

    private fun applyEditorInfo(info: EditorInfo?) {
        val inputType = info?.inputType ?: 0
        val password = InputTypePolicy.isPassword(inputType)
        suppressAutocorrect = InputTypePolicy.suppressesSuggestions(inputType)
        allowWholeFieldEncryption = !password && !suppressAutocorrect
        nullInputType = InputTypePolicy.isNullType(inputType)
        multiline = InputTypePolicy.isMultiLine(inputType)
        if (::binding.isInitialized) {
            binding.suggestionStrip.visibility =
                if (suppressAutocorrect) View.GONE else View.VISIBLE
        }
    }

    private fun capsMode(ic: InputConnection): Int = ic.getCursorCapsMode(
        EditorInfo.TYPE_TEXT_FLAG_CAP_CHARACTERS or
            EditorInfo.TYPE_TEXT_FLAG_CAP_WORDS or
            EditorInfo.TYPE_TEXT_FLAG_CAP_SENTENCES
    )

    private fun updateSuggestions() {
        if (!::binding.isInitialized) return
        val typed = controller.composingText()
        if (typed.isEmpty() || suppressAutocorrect || !settings.autocorrectEnabled) {
            binding.suggestionStrip.suggestions = emptyList()
            return
        }
        val context = connection()?.let { controller.contextBeforeCursor(it) }.orEmpty()
        val alternatives = engine.suggestions(typed, TextProcessing.isSentenceStart(context), SUGGESTION_COUNT)
            .filterNot { it.equals(typed, ignoreCase = true) }
        binding.suggestionStrip.suggestions = listOf(
            alternatives.getOrNull(0).orEmpty(),
            typed,
            alternatives.getOrNull(1).orEmpty()
        )
    }

    /** Persistent warning: auto-append is dangerous, so its ON state stays visible. */
    private fun updatePersistentWarning() {
        if (!::binding.isInitialized) return
        hideStatus?.let { binding.statusText.removeCallbacks(it) }
        hideStatus = null
        if (settings.autoAppendEnabled) {
            binding.statusText.text = AUTO_APPEND_WARNING
            binding.statusText.visibility = View.VISIBLE
        } else {
            binding.statusText.text = ""
            binding.statusText.visibility = View.GONE
        }
    }

    private fun showStatus(message: String) {
        if (!::binding.isInitialized) {
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
            return
        }
        hideStatus?.let { binding.statusText.removeCallbacks(it) }
        binding.statusText.text = message
        binding.statusText.visibility = View.VISIBLE
        val warning = if (settings.autoAppendEnabled) AUTO_APPEND_WARNING else null
        val runnable = Runnable {
            hideStatus = null
            if (warning != null) {
                binding.statusText.text = warning
            } else {
                binding.statusText.visibility = View.GONE
            }
        }
        hideStatus = runnable
        binding.statusText.postDelayed(runnable, STATUS_DURATION_MS)
    }

    private fun showFailure(error: Throwable) {
        showStatus(getString(R.string.status_encryption_unavailable, encryption.describeFailure(error)))
    }

    private fun connection(): TextEditConnection? =
        currentInputConnection?.let { InputConnectionAdapter(it) }

    private fun sendKey(ic: InputConnection, keyCode: Int) {
        ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
        ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
    }

    private fun keyCodeFor(text: String): Int {
        val ch = text.firstOrNull() ?: return KeyEvent.KEYCODE_UNKNOWN
        return when (ch) {
            ' ' -> KeyEvent.KEYCODE_SPACE
            '\n' -> KeyEvent.KEYCODE_ENTER
            ',' -> KeyEvent.KEYCODE_COMMA
            '.' -> KeyEvent.KEYCODE_PERIOD
            '-' -> KeyEvent.KEYCODE_MINUS
            '=' -> KeyEvent.KEYCODE_EQUALS
            ';' -> KeyEvent.KEYCODE_SEMICOLON
            '\'' -> KeyEvent.KEYCODE_APOSTROPHE
            '/' -> KeyEvent.KEYCODE_SLASH
            '@' -> KeyEvent.KEYCODE_AT
            '[' -> KeyEvent.KEYCODE_LEFT_BRACKET
            ']' -> KeyEvent.KEYCODE_RIGHT_BRACKET
            '\\' -> KeyEvent.KEYCODE_BACKSLASH
            '`' -> KeyEvent.KEYCODE_GRAVE
            '*' -> KeyEvent.KEYCODE_STAR
            '#' -> KeyEvent.KEYCODE_POUND
            in 'a'..'z' -> KeyEvent.KEYCODE_A + (ch - 'a')
            in 'A'..'Z' -> KeyEvent.KEYCODE_A + (ch - 'A')
            in '0'..'9' -> KeyEvent.KEYCODE_0 + (ch - '0')
            else -> KeyEvent.KEYCODE_UNKNOWN
        }
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

    private class InputConnectionAdapter(private val ic: InputConnection) : TextEditConnection {

        /** API 33+ snapshot keeps before/after reads from a single editor state. */
        fun takeSnapshot() {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) ic.takeSnapshot()
        }

        override fun commitText(text: String, newCursorPosition: Int): Boolean =
            ic.commitText(text, newCursorPosition)

        override fun setComposingText(text: String, newCursorPosition: Int): Boolean =
            ic.setComposingText(text, newCursorPosition)

        override fun finishComposingText(): Boolean = ic.finishComposingText()

        override fun beginBatchEdit(): Boolean = ic.beginBatchEdit()

        override fun endBatchEdit(): Boolean = ic.endBatchEdit()

        override fun getTextBeforeCursor(maxLength: Int, flags: Int): CharSequence? =
            ic.getTextBeforeCursor(maxLength, flags)

        override fun getTextAfterCursor(maxLength: Int, flags: Int): CharSequence? =
            ic.getTextAfterCursor(maxLength, flags)

        override fun getSelectedText(flags: Int): CharSequence? = ic.getSelectedText(flags)

        override fun deleteSurroundingTextInCodePoints(beforeLength: Int, afterLength: Int): Boolean =
            ic.deleteSurroundingTextInCodePoints(beforeLength, afterLength)

        override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean =
            ic.deleteSurroundingText(beforeLength, afterLength)

        override fun setSelection(start: Int, end: Int): Boolean = ic.setSelection(start, end)
    }

    private companion object {
        const val SUGGESTION_COUNT = 3
        const val STATUS_DURATION_MS = 4000L
        const val AUTO_APPEND_WARNING = "Auto-append ON"
    }
}
