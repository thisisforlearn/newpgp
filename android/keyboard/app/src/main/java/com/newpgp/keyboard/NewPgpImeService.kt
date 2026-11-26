// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Vaibhav - https://github.com/thisisforlearn
package com.newpgp.keyboard

import android.app.AlertDialog
import android.content.ClipboardManager
import android.content.Context
import android.content.DialogInterface
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.widget.Toast
import com.newpgp.keyboard.ai.Tone
import com.newpgp.keyboard.ai.ToneEngine
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
 * declared [PgpBridge], now backed by the real native engine when the bundled library
 * is present. Settings and learned-word counts are the sole app-private persisted data.
 */
class NewPgpImeService : InputMethodService(),
    KeyboardView.OnKeyListener,
    SuggestionStripView.OnSuggestionListener {

    private lateinit var binding: InputViewBinding
    private lateinit var engine: AutocorrectEngine
    private lateinit var settings: SettingsStore
    private lateinit var bridge: PgpBridge
    private lateinit var encryption: EncryptionController
    private lateinit var shield: ShieldEncryptor
    private lateinit var controller: ImeTextController
    private var toneEngine: ToneEngine? = null

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
        bridge = PgpBridgeProvider.current
        // No key-password preference exists in SettingsStore yet (owned by another
        // agent). Read defensively from the same app-private preferences file so a
        // password saved by a future settings UI is honoured; otherwise pass "".
        encryption = EncryptionController(bridge, passwordProvider = { savedKeyPassword() })
        controller = ImeTextController(
            engine = engine,
            autocorrectEnabled = { settings.autocorrectEnabled && !suppressAutocorrect },
            onComposingChanged = { updateSuggestions() }
        )
        shield = ShieldEncryptor(controller, encryption)
        bridge.initialize(filesDir.absolutePath)
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
        toneEngine?.close()
        toneEngine = null
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
        KeyType.SHIELD -> handleEncrypt() // toolbar alias of the shield flow
        KeyType.MORE -> showMoreMenu() // explicit whole-field action + safety number
        KeyType.AI_TONE -> handleAiTone()
        KeyType.CLIPBOARD -> handleClipboardPaste()
        KeyType.MIC -> {
            showStatus(getString(R.string.pgp_status_voice_unavailable))
            true
        }
        KeyType.GIF -> {
            showStatus(getString(R.string.pgp_status_gif_unavailable))
            true
        }
        KeyType.BRAND -> false // decorative row item, never dispatched
        KeyType.SPACER -> false
        else -> false // forward compatibility with toolbar additions
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
                encryption.encrypt(snapshot.text, savedKeyPassword())
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
     * Explicit shield mode: encrypt the CURRENT composing word or an active selection.
     * It never silently falls back to the whole field — that is a separate, explicit
     * toolbar action below. On failure nothing is committed and the typed text stays.
     */
    private fun handleEncrypt(): Boolean {
        val rawIc = currentInputConnection ?: return false
        val ic = InputConnectionAdapter(rawIc)
        ic.takeSnapshot()
        val typed = controller.composingText()
        val selected = ic.getSelectedText(0)?.toString().orEmpty()
        val plaintext = typed.takeIf { it.isNotEmpty() } ?: selected
        if (plaintext.isBlank()) {
            showStatus(getString(R.string.pgp_status_select_or_type))
            return true
        }

        val generation = controller.generation
        shield.run(ic, plaintext, savedKeyPassword())
            .onSuccess { if (stillCurrent(generation, rawIc)) announceEncrypted() }
            .onFailure { error -> if (stillCurrent(generation, rawIc)) showFailure(error) }
        return true
    }

    /** "More tools" menu: the only toolbar entry point for whole-field encryption. */
    private fun showMoreMenu(): Boolean {
        AlertDialog.Builder(this)
            .setTitle(R.string.pgp_more_title)
            .setItems(
                arrayOf<CharSequence>(
                    getString(R.string.pgp_action_encrypt_whole_message),
                    getString(R.string.pgp_more_safety_number)
                )
            ) { _: DialogInterface, which: Int ->
                if (which == 0) handleEncryptWholeMessage() else showSafetyNumberDialog()
            }
            .setNegativeButton(R.string.pgp_dialog_cancel, null)
            .show()
        return true
    }

    /** Lazily created so the model directory is touched only when first needed. */
    private fun ensureToneEngine(): ToneEngine = toneEngine ?: ToneEngine(
        modelRoot = filesDir.absolutePath + "/models"
    ).also { toneEngine = it }

    /** Runs a callback on the IME main thread; the rewrite callback is background. */
    private fun postOnUi(action: () -> Unit) {
        if (::binding.isInitialized) {
            binding.root.post(action)
        } else {
            Handler(Looper.getMainLooper()).post(action)
        }
    }

    /**
     * Offline tone rewrite. [ToneEngine.rewriteAsync] keeps model work off the UI
     * thread; only a successful, non-blank rewrite replaces the composing region.
     */
    private fun handleAiTone(): Boolean {
        val rawIc = currentInputConnection ?: return false
        val source = controller.composingText()
        if (source.isBlank()) {
            showStatus(getString(R.string.pgp_ai_no_text))
            return true
        }
        val ic = InputConnectionAdapter(rawIc)
        val generation = controller.generation
        ensureToneEngine().rewriteAsync(source, Tone.PROFESSIONAL) { result ->
            val rewritten = result.getOrNull()
            when {
                result.isFailure -> postOnUi {
                    if (stillCurrent(generation, rawIc)) {
                        showStatus(getString(R.string.pgp_ai_failed))
                    }
                }
                rewritten.isNullOrBlank() || rewritten == source -> postOnUi {
                    if (stillCurrent(generation, rawIc)) {
                        showStatus(getString(R.string.pgp_ai_no_change))
                    }
                }
                else -> postOnUi {
                    if (!stillCurrent(generation, rawIc) || controller.composingText() != source) {
                        return@postOnUi
                    }
                    ic.beginBatchEdit()
                    try {
                        ic.commitText(rewritten, 1)
                    } finally {
                        ic.endBatchEdit()
                    }
                    controller.resetComposing()
                    if (::binding.isInitialized) {
                        hideStatus?.let { binding.statusText.removeCallbacks(it) }
                        binding.statusText.text = getString(R.string.status_chip_ai_rewritten)
                        binding.statusText.visibility = View.VISIBLE
                    }
                }
            }
        }
        return true
    }

    /** Toolbar paste: text goes straight into the editor, never into a log. */
    private fun handleClipboardPaste(): Boolean {
        val ic = connection() ?: return false
        val manager = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        val pasted = manager?.primaryClip?.getItemAt(0)?.text?.toString().orEmpty()
        if (pasted.isEmpty()) {
            showStatus(getString(R.string.pgp_status_clipboard_empty))
            return true
        }
        ic.commitText(pasted, 1)
        return true
    }

    /**
     * Explicit "Encrypt whole message" toolbar action. It is the ONLY automatic-free
     * whole-field path; nothing on Enter or on key input invokes it.
     */
    private fun handleEncryptWholeMessage(): Boolean {
        val rawIc = currentInputConnection ?: return false
        if (!allowWholeFieldEncryption) {
            showStatus(getString(R.string.status_encryption_unavailable, getString(R.string.pgp_status_disabled_for_field)))
            return true
        }
        val ic = InputConnectionAdapter(rawIc)
        ic.takeSnapshot()
        val snapshot = controller.wholeFieldSnapshot(ic)
        if (snapshot.text.isBlank()) {
            showStatus(getString(R.string.status_nothing_to_encrypt))
            return true
        }

        val generation = controller.generation
        shield.run(ic, snapshot.text, savedKeyPassword(), snapshot.fieldLength)
            .onSuccess { if (stillCurrent(generation, rawIc)) announceEncrypted() }
            .onFailure { error -> if (stillCurrent(generation, rawIc)) showFailure(error) }
        return true
    }

    /** Visible success toast with a safety number (or fingerprint) to compare. */
    private fun announceEncrypted() {
        showStatus(getString(R.string.status_chip_encrypted))
        val number = encryption.safetyNumber().getOrNull()?.takeIf { it.isNotBlank() }
        val fingerprint = encryption.fingerprint()?.takeIf { it.isNotBlank() }
        val message = when {
            number != null -> getString(R.string.pgp_toast_encrypted_with_safety, number)
            fingerprint != null -> getString(R.string.pgp_toast_encrypted_with_fingerprint, fingerprint)
            else -> getString(R.string.toast_encrypted)
        }
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    /**
     * Hook for the key-creation flow: show a plain-language comparison dialog right
     * after a local identity is created. The keyboard settings UI lives in files owned
     * by another agent, so this is exposed rather than auto-triggered here.
     */
    fun createIdentityAndOfferSafetyCheck(name: String, email: String, password: String) {
        bridge.generateIdentity(name, email, password)
            .onSuccess { showSafetyNumberDialog(email) }
            .onFailure { error -> showFailure(error) }
    }

    /** Shown after key creation so the user can verify the number out of band. */
    fun showSafetyNumberDialog(email: String = "") {
        val number = bridge.safetyNumber(email).getOrNull()
        if (number.isNullOrBlank()) {
            showStatus(getString(R.string.pgp_safety_unavailable))
            return
        }
        val message = getString(R.string.pgp_safety_dialog_message) + "\n\n" +
            getString(R.string.pgp_safety_dialog_value, number)
        AlertDialog.Builder(this)
            .setTitle(R.string.pgp_safety_dialog_title)
            .setMessage(message)
            .setPositiveButton(R.string.pgp_safety_dialog_got_it, null)
            .show()
    }

    /**
     * SettingsStore has no key-password field yet. Read the same app-private file
     * defensively so a password saved by a future settings UI works; otherwise "".
     */
    private fun savedKeyPassword(): String {
        val prefs = getSharedPreferences(SETTINGS_PREFS_NAME, MODE_PRIVATE)
        for (key in KEY_PASSWORD_KEYS) {
            val value = prefs.getString(key, null)
            if (!value.isNullOrEmpty()) return value
        }
        return ""
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
            binding.statusText.text = getString(R.string.status_chip_auto_append)
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
        val warning = if (settings.autoAppendEnabled) getString(R.string.status_chip_auto_append) else null
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
        const val SETTINGS_PREFS_NAME = "newpgp_keyboard_settings"

        /** No dedicated key-password preference exists yet; accept likely names. */
        val KEY_PASSWORD_KEYS = arrayOf("key_password", "identity_password", "pgp_password")
    }
}
