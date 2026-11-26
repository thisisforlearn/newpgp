// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Vaibhav - https://github.com/thisisforlearn
@file:OptIn(ExperimentalMaterial3Api::class)

package com.newpgp.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Create
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.newpgp.app.ui.theme.BusyIndicator
import com.newpgp.app.ui.theme.LargePrimaryButton
import com.newpgp.app.ui.theme.LargeSecondaryButton
import com.newpgp.app.ui.theme.MonospaceBlock
import com.newpgp.app.ui.theme.NewPgpTheme
import com.newpgp.app.ui.theme.PreviewStatusChip
import com.newpgp.app.ui.theme.SectionCard
import com.newpgp.app.ui.theme.StatusBanner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Main entry point. All crypto goes through [BridgeProvider.current]. */
class MainActivity : ComponentActivity() {
    private val model: AppModel by lazy { AppModel(BridgeProvider.current) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            NewPgpTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    NewPgpApp(
                        model = model,
                        onCopy = { text -> copyToClipboard(text) },
                        onShare = { text -> shareAsText(text) },
                    )
                }
            }
        }
    }

    /** Clipboard writes are explicit: they only happen when the user taps Copy. */
    private fun copyToClipboard(text: String) {
        val manager = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        manager.setPrimaryClip(ClipData.newPlainText("NewPGP", text))
    }

    private fun shareAsText(text: String) {
        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        startActivity(Intent.createChooser(sendIntent, getString(R.string.button_share)))
    }
}

@Composable
private fun NewPgpApp(
    model: AppModel,
    onCopy: (String) -> Unit,
    onShare: (String) -> Unit,
) {
    val state by model.state.collectAsState()
    val scope = rememberCoroutineScope()
    var selectedTab by remember { mutableIntStateOf(0) }
    val wizard = remember { WizardStateMachine(isRealCrypto = state.isRealCrypto) }
    val wizardState by wizard.state.collectAsState()

    // Only a real bridge may load contacts. The stub must not produce a
    // spurious error banner at startup; the status chip carries that truth.
    LaunchedEffect(state.isRealCrypto) {
        if (state.isRealCrypto && state.contacts.isEmpty()) model.refreshContacts()
    }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text(text = stringResource(R.string.app_name)) })
        },
        bottomBar = {
            TabBar(selected = selectedTab, onSelect = { selectedTab = it })
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(innerPadding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Persistent, impossible to miss, on every step and every tab.
            PreviewStatusChip(state.isRealCrypto, state.bridgeName)

            state.statusMessage?.let { message ->
                StatusBanner(
                    message = message,
                    isError = state.statusIsError,
                    onDismiss = model::dismissStatus,
                )
            }
            if (state.isBusy) BusyIndicator()

            val showWizard = state.identity == null ||
                (wizardState.step == WizardStep.READY && !wizardState.finished)
            if (showWizard) {
                SetupWizard(
                    model = model,
                    wizard = wizard,
                    wizardState = wizardState,
                    scope = scope,
                    state = state,
                    onCopy = onCopy,
                    onShare = onShare,
                )
            } else {
                KeyCard(state = state, onCopy = onCopy, model = model, scope = scope)
            }
            when (selectedTab) {
                0 -> WriteTab(model = model, scope = scope, state = state, onCopy = onCopy, onShare = onShare)
                1 -> ReadTab(model = model, scope = scope, state = state)
                2 -> PeopleTab(model = model, scope = scope, state = state)
                else -> FilesTab()
            }
        }
    }
}

@Composable
private fun TabBar(selected: Int, onSelect: (Int) -> Unit) {
    val items: List<Pair<Int, ImageVector>> = listOf(
        R.string.tab_write to Icons.Filled.Create,
        R.string.tab_read to Icons.Filled.Email,
        R.string.tab_people to Icons.Filled.Group,
        R.string.tab_files to Icons.Filled.InsertDriveFile,
    )
    NavigationBar {
        items.forEachIndexed { index, item ->
            NavigationBarItem(
                selected = selected == index,
                onClick = { onSelect(index) },
                icon = { Icon(imageVector = item.second, contentDescription = null) },
                label = { Text(text = stringResource(item.first)) },
            )
        }
    }
}

/** Small, static progress row. Step 3 is visibly locked, never clickable. */
@Composable
private fun WizardProgress(step: WizardStep, isRealCrypto: Boolean) {
    val labels = listOf(
        R.string.wizard_step_welcome,
        R.string.wizard_step_make_key,
        R.string.wizard_step_ready,
    )
    Row(modifier = Modifier.fillMaxWidth()) {
        labels.forEachIndexed { index, label ->
            val current = step.ordinal == index
            val locked = index == 2 && !isRealCrypto
            Text(
                text = "${index + 1}. ${stringResource(label)}",
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyLarge,
                color = when {
                    current -> MaterialTheme.colorScheme.primary
                    locked -> MaterialTheme.colorScheme.onSurfaceVariant
                    else -> MaterialTheme.colorScheme.onSurface
                },
            )
        }
    }
}

/** Three-step, five-minute setup. Every outcome is honest about the bridge. */
@Composable
private fun SetupWizard(
    model: AppModel,
    wizard: WizardStateMachine,
    wizardState: WizardUiState,
    scope: CoroutineScope,
    state: AppUiState,
    onCopy: (String) -> Unit,
    onShare: (String) -> Unit,
) {
    SectionCard(title = stringResource(R.string.wizard_progress, wizardState.step.ordinal + 1)) {
        WizardProgress(step = wizardState.step, isRealCrypto = state.isRealCrypto)
        Spacer(Modifier.height(16.dp))

        when (wizardState.step) {
            WizardStep.WELCOME -> {
                Text(
                    text = stringResource(R.string.wizard_step_welcome),
                    style = MaterialTheme.typography.titleLarge,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.wizard_welcome_body),
                    style = MaterialTheme.typography.bodyLarge,
                )
                Spacer(Modifier.height(16.dp))
                LargePrimaryButton(
                    text = stringResource(R.string.wizard_get_started),
                    onClick = wizard::getStarted,
                )
            }

            WizardStep.MAKE_KEY -> {
                Text(
                    text = stringResource(R.string.wizard_step_make_key),
                    style = MaterialTheme.typography.titleLarge,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.wizard_make_key_body),
                    style = MaterialTheme.typography.bodyLarge,
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = wizardState.displayName,
                    onValueChange = wizard::setDisplayName,
                    label = { Text(stringResource(R.string.field_display_name)) },
                    supportingText = {
                        Text(
                            text = stringResource(R.string.wizard_name_helper),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                    singleLine = true,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = wizardState.email,
                    onValueChange = wizard::setEmail,
                    label = { Text(stringResource(R.string.field_email)) },
                    supportingText = {
                        Text(
                            text = stringResource(R.string.wizard_email_helper),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                    singleLine = true,
                )
                Spacer(Modifier.height(12.dp))
                LargePrimaryButton(
                    text = stringResource(R.string.button_create_key),
                    onClick = {
                        when (val action = wizard.requestKeyCreation()) {
                            is WizardKeyAction.Create -> scope.launch {
                                val result = model.createIdentity(action.email, action.displayName)
                                result.onSuccess(wizard::onKeyCreated)
                                    .onFailure { wizard.onKeyCreationFailed() }
                            }

                            WizardKeyAction.Blocked -> Unit
                        }
                    },
                    enabled = !state.isBusy,
                )
                Spacer(Modifier.height(8.dp))
                LargeSecondaryButton(
                    text = stringResource(R.string.wizard_copy_project_link),
                    onClick = { onCopy(stringResource(R.string.project_link)) },
                )

                wizardState.blockedReason?.let { reason ->
                    Spacer(Modifier.height(12.dp))
                    StatusBanner(
                        message = stringResource(blockedMessage(reason)),
                        isError = true,
                        onDismiss = wizard::dismissBlocked,
                    )
                }

                Spacer(Modifier.height(12.dp))
                LargeSecondaryButton(
                    text = stringResource(R.string.wizard_step_ready),
                    onClick = {},
                    enabled = false,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.wizard_step3_locked),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            WizardStep.READY -> {
                Text(
                    text = stringResource(R.string.wizard_step_ready),
                    style = MaterialTheme.typography.titleLarge,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.wizard_ready_body),
                    style = MaterialTheme.typography.bodyLarge,
                )
                Spacer(Modifier.height(12.dp))
                wizardState.fingerprint?.let { fingerprint ->
                    Text(
                        text = stringResource(R.string.wizard_fingerprint_label),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    MonospaceBlock(text = fingerprint)
                }
                Spacer(Modifier.height(12.dp))
                LargeSecondaryButton(
                    text = stringResource(R.string.button_share_public_key),
                    onClick = { wizardState.publicKeyArmored?.let(onShare) },
                    enabled = wizardState.publicKeyArmored != null,
                )
                Spacer(Modifier.height(8.dp))
                LargePrimaryButton(
                    text = stringResource(R.string.wizard_finish),
                    onClick = wizard::finish,
                )
            }
        }
    }
}

private fun blockedMessage(reason: WizardBlockedReason): Int = when (reason) {
    WizardBlockedReason.ENGINE_NOT_CONNECTED -> R.string.wizard_key_unavailable
    WizardBlockedReason.MISSING_FIELDS -> R.string.wizard_missing_fields
    WizardBlockedReason.KEY_CREATION_FAILED -> R.string.wizard_key_failed
}

@Composable
private fun KeyCard(
    state: AppUiState,
    onCopy: (String) -> Unit,
    model: AppModel,
    scope: CoroutineScope,
) {
    val identity = state.identity ?: return
    SectionCard(title = stringResource(R.string.key_ready_title)) {
        Text(text = identity.displayName, style = MaterialTheme.typography.bodyLarge)
        Text(text = identity.email, style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(8.dp))
        MonospaceBlock(text = "Fingerprint: ${identity.fingerprint}")
        Spacer(Modifier.height(8.dp))
        Row {
            FilledTonalButton(onClick = { scope.launch { model.refreshPublicKey() } }) {
                Text(text = "Show public key")
            }
            Spacer(Modifier.width(8.dp))
            state.publicKeyArmored?.let { key ->
                FilledTonalButton(onClick = { onCopy(key) }) {
                    Text(text = stringResource(R.string.button_copy_public_key))
                }
            }
        }
        state.publicKeyArmored?.let { MonospaceBlock(text = it) }
    }
}

@Composable
private fun WriteTab(
    model: AppModel,
    scope: CoroutineScope,
    state: AppUiState,
    onCopy: (String) -> Unit,
    onShare: (String) -> Unit,
) {
    var message by remember { mutableStateOf("") }
    var recipients by remember { mutableStateOf("") }
    var pgpOnly by remember { mutableStateOf(false) }

    SectionCard(title = stringResource(R.string.write_title)) {
        OutlinedTextField(
            value = message,
            onValueChange = { message = it },
            label = { Text(stringResource(R.string.field_message)) },
            modifier = Modifier.fillMaxWidth(),
            minLines = 4,
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = recipients,
            onValueChange = { recipients = it },
            label = { Text(stringResource(R.string.field_recipients)) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Switch(checked = pgpOnly, onCheckedChange = { pgpOnly = it })
            Spacer(Modifier.width(8.dp))
            Text(text = stringResource(R.string.toggle_pgp_only), style = MaterialTheme.typography.bodyLarge)
        }
        Spacer(Modifier.height(8.dp))
        Text(text = stringResource(R.string.write_explainer), style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(12.dp))
        Button(
            onClick = {
                val recipientList = recipients.split(',').map { it.trim() }.filter { it.isNotEmpty() }
                scope.launch { model.prepareShare(message, recipientList, pgpOnly) }
            },
            enabled = !state.isBusy && message.isNotBlank(),
        ) {
            Text(text = stringResource(R.string.button_prepare_message))
        }

        state.lastComposed?.let { output ->
            Spacer(Modifier.height(12.dp))
            Text(text = stringResource(R.string.result_ready), style = MaterialTheme.typography.bodyLarge)
            MonospaceBlock(text = output)
            Spacer(Modifier.height(8.dp))
            Row {
                FilledTonalButton(onClick = { onCopy(output) }) {
                    Text(text = stringResource(R.string.button_copy))
                }
                Spacer(Modifier.width(8.dp))
                FilledTonalButton(onClick = { onShare(output) }) {
                    Text(text = stringResource(R.string.button_share))
                }
            }
        }
    }
}

@Composable
private fun ReadTab(model: AppModel, scope: CoroutineScope, state: AppUiState) {
    var armored by remember { mutableStateOf("") }

    SectionCard(title = stringResource(R.string.read_title)) {
        OutlinedTextField(
            value = armored,
            onValueChange = { armored = it },
            label = { Text(stringResource(R.string.field_armored)) },
            modifier = Modifier.fillMaxWidth(),
            minLines = 5,
        )
        Spacer(Modifier.height(12.dp))
        Button(
            onClick = { scope.launch { model.decrypt(armored) } },
            enabled = !state.isBusy && armored.isNotBlank(),
        ) {
            Text(text = stringResource(R.string.button_decrypt))
        }

        state.lastDecryption?.let { result ->
            Spacer(Modifier.height(12.dp))
            val signatureLabel = when (result.signatureStatus) {
                SignatureStatus.VERIFIED -> R.string.signature_verified
                SignatureStatus.SIGNED_UNVERIFIED -> R.string.signature_unverified
                SignatureStatus.NOT_SIGNED -> R.string.signature_none
                SignatureStatus.UNKNOWN -> R.string.signature_unknown
            }
            Text(text = stringResource(signatureLabel), style = MaterialTheme.typography.bodyLarge)
            result.signerFingerprint?.let { MonospaceBlock(text = "Signer: $it") }
            Spacer(Modifier.height(8.dp))
            MonospaceBlock(text = result.plaintext)
        }
    }
}

@Composable
private fun PeopleTab(model: AppModel, scope: CoroutineScope, state: AppUiState) {
    var lookup by remember { mutableStateOf("") }
    var enteredNumber by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf<Contact?>(null) }
    val selectedContact = selected?.let { chosen ->
        state.contacts.firstOrNull { it.email.equals(chosen.email, ignoreCase = true) } ?: chosen
    }

    LaunchedEffect(selectedContact?.email) {
        selectedContact?.let { model.safetyNumber(it) }
    }

    SectionCard(title = stringResource(R.string.people_title)) {
        OutlinedTextField(
            value = lookup,
            onValueChange = { lookup = it },
            label = { Text(stringResource(R.string.field_lookup_email)) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = { scope.launch { model.discoverKey(lookup) } },
            enabled = !state.isBusy && lookup.isNotBlank(),
        ) {
            Text(text = stringResource(R.string.button_find_key))
        }

        Spacer(Modifier.height(16.dp))
        state.contacts.forEach { contact ->
            SectionCard(title = contact.displayName) {
                Text(text = contact.email, style = MaterialTheme.typography.bodyLarge)
                MonospaceBlock(text = contact.fingerprint)
                if (contact.verified) {
                    Text(text = stringResource(R.string.verified_badge), color = MaterialTheme.colorScheme.primary)
                }
                Spacer(Modifier.height(8.dp))
                FilledTonalButton(onClick = { selected = contact }) {
                    Text(text = stringResource(R.string.button_verify))
                }
            }
            Spacer(Modifier.height(8.dp))
        }

        selectedContact?.let { contact ->
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Safety number for ${contact.displayName}",
                style = MaterialTheme.typography.titleLarge,
            )
            state.safetyNumber?.let { MonospaceBlock(text = it) }
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = enteredNumber,
                onValueChange = { enteredNumber = it },
                label = { Text(stringResource(R.string.field_safety_number)) },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = { scope.launch { model.verifySafetyNumber(contact, enteredNumber) } },
                enabled = !state.isBusy && enteredNumber.isNotBlank(),
            ) {
                Text(text = stringResource(R.string.button_check_number))
            }
        }
    }
}

@Composable
private fun FilesTab() {
    SectionCard(title = stringResource(R.string.tab_files)) {
        Text(text = stringResource(R.string.files_preview_body), style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(12.dp))
        Button(onClick = { }, enabled = false) {
            Text(text = stringResource(R.string.button_share_file_soon))
        }
    }
}
