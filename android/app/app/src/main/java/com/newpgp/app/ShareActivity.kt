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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.newpgp.app.ui.theme.BusyIndicator
import com.newpgp.app.ui.theme.MonospaceBlock
import com.newpgp.app.ui.theme.NewPgpTheme
import com.newpgp.app.ui.theme.PreviewStatusChip
import com.newpgp.app.ui.theme.SectionCard
import com.newpgp.app.ui.theme.StatusBanner
import kotlinx.coroutines.launch

/**
 * Explicit share target. It only accepts a single ACTION_SEND of text/plain
 * from another app; there is no IME, no background service, no clipboard
 * polling, and no automatic capture of anything the user types elsewhere.
 * Encryption happens only after the user taps one of the two buttons.
 */
class ShareActivity : ComponentActivity() {
    private val model: AppModel by lazy { AppModel(BridgeProvider.current) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val sharedText = intent
            .takeIf { it.action == Intent.ACTION_SEND && it.type?.startsWith("text/") == true }
            ?.getStringExtra(Intent.EXTRA_TEXT)
            .orEmpty()
            .trim()

        setContent {
            NewPgpTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    ShareScreen(
                        model = model,
                        sharedText = sharedText,
                        onCopy = { text -> copyToClipboard(text) },
                        onShare = { text -> shareAsText(text) },
                        onDone = { finish() },
                    )
                }
            }
        }
    }

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
private fun ShareScreen(
    model: AppModel,
    sharedText: String,
    onCopy: (String) -> Unit,
    onShare: (String) -> Unit,
    onDone: () -> Unit,
) {
    val state by model.state.collectAsState()
    val scope = rememberCoroutineScope()
    var recipients by remember { mutableStateOf("") }
    var pgpOnly by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(text = stringResource(R.string.share_title)) },
                navigationIcon = {
                    IconButton(onClick = onDone) {
                        Icon(imageVector = Icons.Filled.Close, contentDescription = stringResource(R.string.button_cancel))
                    }
                },
            )
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
            PreviewStatusChip(state.isRealCrypto, state.bridgeName)

            state.statusMessage?.let { message ->
                StatusBanner(
                    message = message,
                    isError = state.statusIsError,
                    onDismiss = model::dismissStatus,
                )
            }
            if (state.isBusy) BusyIndicator()

            if (sharedText.isEmpty()) {
                SectionCard(title = stringResource(R.string.share_title)) {
                    Text(text = stringResource(R.string.share_empty), style = MaterialTheme.typography.bodyLarge)
                }
            } else {
                SectionCard(title = stringResource(R.string.share_title)) {
                    Text(text = stringResource(R.string.share_body), style = MaterialTheme.typography.bodyLarge)
                    Spacer(Modifier.height(12.dp))
                    MonospaceBlock(text = sharedText)
                }

                SectionCard(title = stringResource(R.string.field_recipients)) {
                    OutlinedTextField(
                        value = recipients,
                        onValueChange = { recipients = it },
                        label = { Text(stringResource(R.string.field_recipients)) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(checked = pgpOnly, onCheckedChange = { pgpOnly = it })
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = stringResource(R.string.toggle_pgp_only),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    Row {
                        Button(
                            onClick = {
                                scope.launch {
                                    val list = recipients.split(',').map { it.trim() }.filter { it.isNotEmpty() }
                                    model.prepareShare(sharedText, list, pgpOnly).getOrNull()?.let(onCopy)
                                }
                            },
                            enabled = !state.isBusy,
                        ) {
                            Text(text = stringResource(R.string.button_encrypt_and_copy))
                        }
                        Spacer(Modifier.width(8.dp))
                        FilledTonalButton(
                            onClick = {
                                scope.launch {
                                    val list = recipients.split(',').map { it.trim() }.filter { it.isNotEmpty() }
                                    model.prepareShare(sharedText, list, pgpOnly).getOrNull()?.let(onShare)
                                }
                            },
                            enabled = !state.isBusy,
                        ) {
                            Text(text = stringResource(R.string.button_encrypt_and_share))
                        }
                    }
                }

                state.lastComposed?.let { output ->
                    SectionCard(title = stringResource(R.string.result_ready)) {
                        MonospaceBlock(text = output)
                    }
                }
            }
        }
    }
}
