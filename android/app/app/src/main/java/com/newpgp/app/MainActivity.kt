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
import com.newpgp.app.ui.theme.MonospaceBlock
import com.newpgp.app.ui.theme.NewPgpTheme
import com.newpgp.app.ui.theme.PreviewNotice
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

    LaunchedEffect(Unit) {
        if (state.contacts.isEmpty()) model.refreshContacts()
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
            PreviewNotice(state.isRealCrypto, state.bridgeName)

            state.statusMessage?.let { message ->
                StatusBanner(
                    message = message,
                    isError = state.statusIsError,
                    onDismiss = model::dismissStatus,
                )
            }
            if (state.isBusy) BusyIndicator()

            if (state.identity == null) {
                OnboardingScreen(model = model, scope = scope, state = state)
            } else {
                KeyCard(state = state, onCopy = onCopy, model = model, scope = scope)
                when (selectedTab) {
                    0 -> WriteTab(model = model, scope = scope, state = state, onCopy = onCopy, onShare = onShare)
                    1 -> ReadTab(model = model, scope = scope, state = state)
                    2 -> PeopleTab(model = model, scope = scope, state = state)
                    else -> FilesTab()
                }
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

@Composable
private fun OnboardingScreen(model: AppModel, scope: CoroutineScope, state: AppUiState) {
    var name by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }

    SectionCard(title = stringResource(R.string.onboarding_title)) {
        Text(text = stringResource(R.string.onboarding_body), style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text(stringResource(R.string.field_display_name)) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = email,
            onValueChange = { email = it },
            label = { Text(stringResource(R.string.field_email)) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        Spacer(Modifier.height(12.dp))
        Button(
            onClick = { scope.launch { model.createIdentity(email, name) } },
            enabled = !state.isBusy && name.isNotBlank() && email.isNotBlank(),
        ) {
            Text(text = stringResource(R.string.button_create_key))
        }
    }
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
