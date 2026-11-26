// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Vaibhav - https://github.com/thisisforlearn
package com.newpgp.app

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** The three first-run screens, in the only order in which they may be visited. */
enum class WizardStep { WELCOME, MAKE_KEY, READY }

/**
 * Why key creation was stopped. The UI maps each value to a plain-language
 * string; the state machine itself owns no Android resources.
 */
enum class WizardBlockedReason {
    ENGINE_NOT_CONNECTED,
    MISSING_FIELDS,
    KEY_CREATION_FAILED,
}

/** Immutable snapshot rendered by the wizard. */
data class WizardUiState(
    val step: WizardStep = WizardStep.WELCOME,
    val displayName: String = "",
    val email: String = "",
    val fingerprint: String? = null,
    val publicKeyArmored: String? = null,
    val blockedReason: WizardBlockedReason? = null,
    val finished: Boolean = false,
)

/** What the wizard asks the app to do after a tap on "Create my key". */
sealed interface WizardKeyAction {
    /** No bridge call is made and no key is pretended. */
    data object Blocked : WizardKeyAction

    /** A genuine bridge may be asked to create a key with these values. */
    data class Create(val displayName: String, val email: String) : WizardKeyAction
}

/**
 * State machine for the first-run wizard.
 *
 * Deliberately free of Android framework types so JVM tests can drive every
 * rule. Step 3 can only be reached through [onKeyCreated], which itself
 * requires a bridge that reports `isRealCrypto == true`. With the failing
 * [NativePgpBridge] the wizard stays honest: `requestKeyCreation()` returns
 * [WizardKeyAction.Blocked] and no identity, fingerprint, or armor is stored.
 */
class WizardStateMachine(
    private val isRealCrypto: Boolean,
) {
    private val _state = MutableStateFlow(WizardUiState())
    val state: StateFlow<WizardUiState> = _state.asStateFlow()

    /** Advances Welcome -> Make your key only; later taps are harmless. */
    fun getStarted() {
        _state.update { current ->
            if (current.step == WizardStep.WELCOME) {
                current.copy(step = WizardStep.MAKE_KEY)
            } else {
                current
            }
        }
    }

    fun setDisplayName(value: String) {
        _state.update { current ->
            if (current.step == WizardStep.MAKE_KEY) {
                current.copy(displayName = value, blockedReason = null)
            } else {
                current
            }
        }
    }

    fun setEmail(value: String) {
        _state.update { current ->
            if (current.step == WizardStep.MAKE_KEY) {
                current.copy(email = value, blockedReason = null)
            } else {
                current
            }
        }
    }

    /**
     * Gate every key-creation attempt. A preview build is blocked *before*
     * any bridge call, so the mock bridge can never be reached from the
     * wizard and no fake success can appear in the state.
     */
    fun requestKeyCreation(): WizardKeyAction {
        val current = _state.value
        if (!isRealCrypto) {
            _state.update { it.copy(blockedReason = WizardBlockedReason.ENGINE_NOT_CONNECTED) }
            return WizardKeyAction.Blocked
        }
        if (current.step != WizardStep.MAKE_KEY) {
            return WizardKeyAction.Blocked
        }
        if (current.displayName.isBlank() || current.email.isBlank()) {
            _state.update { it.copy(blockedReason = WizardBlockedReason.MISSING_FIELDS) }
            return WizardKeyAction.Blocked
        }
        _state.update { it.copy(blockedReason = null) }
        return WizardKeyAction.Create(current.displayName.trim(), current.email.trim())
    }

    /**
     * Unlocks step 3 only after a genuine bridge success on step 2. Passing
     * a hand-made identity while the bridge is a stub is ignored: the wizard
     * keeps its honest blocked state instead of showing a fake key.
     */
    fun onKeyCreated(identity: Identity) {
        _state.update { current ->
            when {
                !isRealCrypto -> current.copy(
                    blockedReason = WizardBlockedReason.ENGINE_NOT_CONNECTED,
                )

                current.step != WizardStep.MAKE_KEY -> current

                else -> current.copy(
                    step = WizardStep.READY,
                    fingerprint = identity.fingerprint,
                    publicKeyArmored = identity.publicKeyArmored,
                    blockedReason = null,
                )
            }
        }
    }

    fun onKeyCreationFailed() {
        _state.update { it.copy(blockedReason = WizardBlockedReason.KEY_CREATION_FAILED) }
    }

    /** Finishing is only possible from a real, fully populated step 3. */
    fun finish() {
        _state.update { current ->
            if (isRealCrypto &&
                current.step == WizardStep.READY &&
                current.fingerprint != null
            ) {
                current.copy(finished = true)
            } else {
                current
            }
        }
    }

    fun dismissBlocked() {
        _state.update { it.copy(blockedReason = null) }
    }
}
