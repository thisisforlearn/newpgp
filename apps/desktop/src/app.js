// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Vaibhav - https://github.com/thisisforlearn
// NewPGP desktop UI. Talks only to the local Rust backend via Tauri commands.
// No plaintext is ever written to disk by this frontend.

const invoke = (...args) => {
  const internals = window.__TAURI_INTERNALS__ || window.__TAURI__;
  if (!internals || typeof internals.invoke !== 'function') {
    return Promise.reject(new Error('The local NewPGP backend is not available.'));
  }
  return internals.invoke(...args);
};

const $ = (id) => document.getElementById(id);
const toast = (message, isError = false) => {
  const node = $('toast');
  node.textContent = message;
  node.className = `show${isError ? ' error' : ''}`;
  clearTimeout(toast.timer);
  toast.timer = setTimeout(() => { node.className = ''; }, 4500);
};

function showError(error) {
  const message = typeof error === 'string' ? error : (error.message || String(error));
  toast(message, true);
}

document.querySelectorAll('.tabs button').forEach((button) => {
  button.addEventListener('click', () => {
    document.querySelectorAll('.tabs button').forEach((b) => b.classList.remove('active'));
    document.querySelectorAll('.tab').forEach((t) => t.classList.remove('active'));
    button.classList.add('active');
    $(`tab-${button.dataset.tab}`).classList.add('active');
  });
});

async function refreshStatus() {
  try {
    const key = await invoke('my_key');
    $('key-status').textContent = `Your key: ${key.fingerprint}`;
    $('key-status').className = 'status-pill ready';
    $('my-key').innerHTML = `<strong>Fingerprint</strong><pre>${key.prettyFingerprint}</pre>
      <p class="muted">Share this only if someone wants to verify it by phone or in person.</p>`;
  } catch (_) {
    $('key-status').textContent = 'No key yet — create one in the Keys tab';
    $('key-status').className = 'status-pill missing';
    $('my-key').textContent = 'You need a key before you can write encrypted mail.';
  }
}

async function refreshContacts() {
  try {
    const list = await invoke('contacts');
    const node = $('contacts');
    if (!list.length) {
      node.innerHTML = '<p class="muted">No contacts yet. Add a key below, or use the built-in email client to discover keys automatically.</p>';
      return;
    }
    node.innerHTML = `<div class="contacts-list">${list.map((c) => `
      <div class="contact">
        <strong>${escapeHtml(c.email)}</strong>
        <small>${escapeHtml(c.fingerprint)}</small>
        <small>${escapeHtml(c.source)} · ${escapeHtml(c.verification)}</small>
      </div>`).join('')}</div>`;
  } catch (error) { showError(error); }
}

function escapeHtml(text) {
  return String(text).replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
}

$('create-btn').addEventListener('click', async () => {
  try {
    const result = await invoke('create_identity', {
      name: $('new-name').value,
      email: $('new-email').value,
      password: $('new-password').value,
    });
    toast(`Your key is ready. Fingerprint: ${result.fingerprint}`);
    $('new-password').value = '';
    await refreshStatus();
  } catch (error) { showError(error); }
});

$('import-btn').addEventListener('click', async () => {
  try {
    const result = await invoke('import_key', { email: $('import-email').value, armor: $('import-armor').value });
    toast(`Saved key. Safety number: ${result.safetyNumber}`);
    await refreshContacts();
  } catch (error) { showError(error); }
});

$('verify-btn').addEventListener('click', async () => {
  try {
    await invoke('verify_contact', { email: $('verify-email').value, code: $('verify-code').value });
    toast('Verified. We will warn you if this key ever changes.');
    await refreshContacts();
  } catch (error) { showError(error); }
});

$('compose-btn').addEventListener('click', async () => {
  const button = $('compose-btn');
  button.disabled = true;
  try {
    const emails = $('recipients').value.split(',').map((s) => s.trim()).filter(Boolean);
    const mode = document.querySelector('input[name="mode"]:checked').value;
    const result = await invoke('compose_message', { body: $('body').value, recipients: emails, mode });
    $('compose-output').value = result.body;
    $('compose-notices').textContent = result.notices.join(' ');
    toast('Message ready. Copy it into your email app.');
  } catch (error) { showError(error); }
  finally { button.disabled = false; }
});

$('copy-btn').addEventListener('click', async () => {
  try {
    await navigator.clipboard.writeText($('compose-output').value);
    toast('Copied to clipboard.');
  } catch (error) { showError('Could not copy — select the text and copy manually.'); }
});

$('decrypt-btn').addEventListener('click', async () => {
  try {
    const result = await invoke('decrypt_message', { armored: $('decrypt-input').value });
    $('decrypt-output').innerHTML = `<h2>Decrypted</h2><pre>${escapeHtml(result.text)}</pre>
      <p><strong>Signature:</strong> ${escapeHtml(result.signature)}</p>`;
  } catch (error) { showError(error); }
});

refreshStatus();
refreshContacts();
