// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Vaibhav - https://github.com/thisisforlearn
package com.newpgp.app

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext

/** Immutable snapshot rendered by Compose. */
data class AppUiState(
    val bridgeName: String,
    val isRealCrypto: Boolean,
    val identity: Identity? = null,
    val publicKeyArmored: String? = null,
    val contacts: List<Contact> = emptyList(),
    val lastComposed: String? = null,
    val lastDecryption: DecryptionResult? = null,
    val safetyNumber: String? = null,
    val statusMessage: String? = null,
    val statusIsError: Boolean = false,
    val isBusy: Boolean = false,
)

/**
 * State holder for the whole app, intentionally free of Android framework
 * types so JVM unit tests can drive every flow without a device.
 *
 * Bridge calls run on [workDispatcher] (the main thread is never blocked).
 * Results live only in memory: no plaintext, key, or armor is logged or saved.
 */
class AppModel(
    private val bridge: PgpBridge = BridgeProvider.current,
    private val workDispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    private val _state = MutableStateFlow(
        AppUiState(bridgeName = bridge.displayName, isRealCrypto = bridge.isRealCrypto),
    )
    val state: StateFlow<AppUiState> = _state.asStateFlow()

    suspend fun createIdentity(email: String, displayName: String): Result<Identity> {
        setBusy(true)
        val result = call { bridge.createIdentity(email, displayName) }
        result.onSuccess { created ->
            _state.update {
                it.copy(
                    identity = created,
                    statusMessage = KEY_READY,
                    statusIsError = false,
                )
            }
        }.onFailure(::reportFailure)
        setBusy(false)
        return result
    }

    suspend fun refreshPublicKey(): Result<String> {
        setBusy(true)
        val result = call { bridge.publicKey() }
        result.onSuccess { armored -> _state.update { it.copy(publicKeyArmored = armored) } }
            .onFailure(::reportFailure)
        setBusy(false)
        return result
    }

    suspend fun refreshContacts(): Result<List<Contact>> {
        setBusy(true)
        val result = call { bridge.contacts() }
        result.onSuccess { list -> _state.update { it.copy(contacts = list) } }
            .onFailure(::reportFailure)
        setBusy(false)
        return result
    }

    suspend fun discoverKey(email: String): Result<Contact> {
        setBusy(true)
        val result = call { bridge.discoverKey(email) }
        result.onSuccess { found ->
            _state.update { current ->
                val alreadyKnown = current.contacts.any {
                    it.email.equals(found.email, ignoreCase = true)
                }
                val updated = if (alreadyKnown) {
                    current.contacts.map {
                        if (it.email.equals(found.email, ignoreCase = true)) found else it
                    }
                } else {
                    current.contacts + found
                }
                current.copy(
                    contacts = updated,
                    statusMessage = "Key found for ${found.email}.",
                    statusIsError = false,
                )
            }
        }.onFailure(::reportFailure)
        setBusy(false)
        return result
    }

    /**
     * Used by both the Write tab and the share target, so the share flow and
     * the normal flow cannot drift apart. Never mutates the input text.
     */
    suspend fun prepareShare(
        text: String,
        recipients: List<String>,
        pgpOnly: Boolean,
    ): Result<String> {
        setBusy(true)
        val result = call { bridge.compose(text, recipients, pgpOnly) }
        result.onSuccess { output ->
            _state.update {
                it.copy(
                    lastComposed = output,
                    statusMessage = if (pgpOnly) PGP_ONLY_READY else SEND_BOTH_READY,
                    statusIsError = false,
                )
            }
        }.onFailure(::reportFailure)
        setBusy(false)
        return result
    }

    suspend fun decrypt(armored: String): Result<DecryptionResult> {
        setBusy(true)
        val result = call { bridge.decrypt(armored) }
        result.onSuccess { decoded ->
            _state.update {
                it.copy(
                    lastDecryption = decoded,
                    statusMessage = DECRYPTED,
                    statusIsError = false,
                )
            }
        }.onFailure(::reportFailure)
        setBusy(false)
        return result
    }

    suspend fun safetyNumber(contact: Contact): Result<String> {
        val result = call { bridge.safetyNumber(contact) }
        result.onSuccess { number -> _state.update { it.copy(safetyNumber = number) } }
            .onFailure(::reportFailure)
        return result
    }

    suspend fun verifySafetyNumber(contact: Contact, enteredNumber: String): Result<SafetyNumberResult> {
        setBusy(true)
        val result = call { bridge.verifySafetyNumber(contact, enteredNumber) }
        result.onSuccess { verification ->
            _state.update { current ->
                current.copy(
                    contacts = current.contacts.map {
                        if (it.email.equals(verification.contact.email, ignoreCase = true)) {
                            verification.contact
                        } else {
                            it
                        }
                    },
                    safetyNumber = verification.canonicalNumber,
                    statusMessage = if (verification.matches) SAFETY_MATCH else SAFETY_MISMATCH,
                    statusIsError = !verification.matches,
                )
            }
        }.onFailure(::reportFailure)
        setBusy(false)
        return result
    }

    fun dismissStatus() {
        _state.update { it.copy(statusMessage = null, statusIsError = false) }
    }

    private fun setBusy(value: Boolean) {
        _state.update { it.copy(isBusy = value) }
    }

    private fun reportFailure(error: Throwable) {
        _state.update {
            it.copy(statusMessage = error.message ?: GENERIC_ERROR, statusIsError = true)
        }
    }

    private suspend fun <T> call(block: () -> Result<T>): Result<T> =
        withContext(workDispatcher) {
            try {
                block()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                Result.failure(error)
            }
        }

    companion object {
        const val KEY_READY: String = "Your key is ready. Share your public key with friends."
        const val SEND_BOTH_READY: String = "Message ready: your words plus the encrypted block."
        const val PGP_ONLY_READY: String = "Encrypted block ready. No plain text is included."
        const val DECRYPTED: String = "Message decrypted. Check the signature status below."
        const val SAFETY_MATCH: String = "Numbers match. This contact is verified."
        const val SAFETY_MISMATCH: String = "Numbers do not match. Do not trust this contact."
        const val GENERIC_ERROR: String = "Something went wrong. Please try again."
    }
}
