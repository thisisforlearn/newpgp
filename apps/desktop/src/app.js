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

// Friendly, visible help when a command fails. The technical message is tucked
// away in a details block so it never scares the person reading the screen.
function showError(error, options = {}) {
  const message = typeof error === 'string' ? error : (error && error.message) || String(error);
  toast(message, true);
  if (options.banner === false) return;
  $('error-banner-detail').textContent = message;
  $('error-banner').hidden = false;
}

$('error-banner-close').addEventListener('click', () => {
  $('error-banner').hidden = true;
});

function activateTab(name) {
  document.querySelectorAll('.tabs button').forEach((button) => {
    const active = button.dataset.tab === name;
    button.classList.toggle('active', active);
    button.setAttribute('aria-selected', String(active));
  });
  document.querySelectorAll('.tab').forEach((tab) => {
    tab.classList.toggle('active', tab.id === `tab-${name}`);
  });
}

document.querySelectorAll('.tabs button').forEach((button) => {
  button.addEventListener('click', () => activateTab(button.dataset.tab));
});

function prettyFingerprint(key) {
  if (!key) return '';
  if (key.prettyFingerprint) return String(key.prettyFingerprint);
  const hex = String(key.fingerprint || '').replace(/[^0-9a-fA-F]/g, '');
  return hex.replace(/(.{4})/g, '$1 ').trim();
}

function renderKey(key) {
  $('key-status').textContent = `Your key: ${key.fingerprint}`;
  $('key-status').className = 'status-pill ready';
  const node = $('my-key');
  node.textContent = '';
  const label = document.createElement('strong');
  label.textContent = 'Fingerprint';
  node.appendChild(label);
  const pre = document.createElement('pre');
  pre.textContent = prettyFingerprint(key);
  node.appendChild(pre);
  const note = document.createElement('p');
  note.className = 'muted';
  note.textContent = 'Share this only if someone wants to verify it by phone or in person.';
  node.appendChild(note);
}

function renderNoKey() {
  $('key-status').textContent = 'No key yet — create one in the Keys tab';
  $('key-status').className = 'status-pill missing';
  $('my-key').textContent = 'You need a key before you can write encrypted mail.';
}

// A missing key on first run is normal; any other failure is a real problem.
function isNoKeyError(error) {
  const message = String(error && error.message ? error.message : error).toLowerCase();
  return /haven't created|no key|no_key|key yet|not found/.test(message);
}

async function tryLoadKey() {
  try {
    const key = await invoke('my_key');
    renderKey(key);
    return { key, error: null };
  } catch (error) {
    renderNoKey();
    return { key: null, error };
  }
}

async function refreshContacts() {
  try {
    const list = await invoke('contacts');
    const node = $('contacts');
    node.textContent = '';
    if (!list.length) {
      const empty = document.createElement('p');
      empty.className = 'muted';
      empty.textContent = 'No contacts yet. Add a key below, or use the built-in email client to discover keys automatically.';
      node.appendChild(empty);
      return;
    }
    const wrap = document.createElement('div');
    wrap.className = 'contacts-list';
    list.forEach((contact) => {
      const card = document.createElement('div');
      card.className = 'contact';
      const email = document.createElement('strong');
      email.textContent = String(contact.email || '');
      card.appendChild(email);
      const fingerprint = document.createElement('small');
      fingerprint.textContent = String(contact.fingerprint || '');
      card.appendChild(fingerprint);
      const meta = document.createElement('small');
      meta.textContent = `${contact.source || ''} · ${contact.verification || ''}`;
      card.appendChild(meta);
      wrap.appendChild(card);
    });
    node.appendChild(wrap);
  } catch (error) { showError(error); }
}

async function copyText(text) {
  if (navigator.clipboard && navigator.clipboard.writeText) {
    try {
      await navigator.clipboard.writeText(text);
      return true;
    } catch (_) { /* fall through to the selection method */ }
  }
  const area = document.createElement('textarea');
  area.value = text;
  area.setAttribute('readonly', '');
  area.style.position = 'fixed';
  area.style.opacity = '0';
  document.body.appendChild(area);
  area.select();
  let copied = false;
  try { copied = document.execCommand('copy'); } catch (_) { copied = false; }
  area.remove();
  return copied;
}

// ---------------------------------------------------------------------------
// First-run setup wizard. It is a full-screen card, not one of the tabs.
// ---------------------------------------------------------------------------

let wizardKey = null;
let wizardStep = 1;

const wizardTitles = {
  1: 'Make your key',
  2: "You're ready",
  3: 'Send your first private message',
};
const wizardFocus = { 1: 'wizard-name', 2: 'wizard-copy', 3: 'wizard-example' };

function setWizardOpen(open) {
  $('wizard').hidden = !open;
  [document.querySelector('header'), document.querySelector('nav.tabs'), document.querySelector('main')].forEach((part) => {
    if (!part) return;
    part.inert = open;
    if (open) part.setAttribute('aria-hidden', 'true');
    else part.removeAttribute('aria-hidden');
  });
}

function primeWriteTab() {
  $('recipients').placeholder = 'Example: alice@example.com';
}

function showWizardStep(step) {
  wizardStep = step;
  for (let number = 1; number <= 3; number += 1) {
    $(`wizard-step-${number}`).hidden = number !== step;
  }
  $('wizard-title').textContent = wizardTitles[step];
  $('wizard-progress').textContent = `Step ${step} of 3 — about 5 minutes in total`;
  $('wizard-hint').textContent = '';
  if (step === 2) $('wizard-fingerprint').textContent = prettyFingerprint(wizardKey);
  if (step === 3) primeWriteTab();
  $(wizardFocus[step]).focus();
}

function openWizard(step = 1) {
  setWizardOpen(true);
  showWizardStep(step);
}

function closeWizard() {
  setWizardOpen(false);
}

function finishWizard(fillExample) {
  if (fillExample) {
    $('recipients').value = 'alice@example.com';
    $('body').value = 'Hi Alice,\n\nThis is my first private message. A private copy is added below.\n\n— Me';
  }
  activateTab('compose');
  closeWizard();
  $('body').focus();
}

$('wizard-create').addEventListener('click', async () => {
  const name = $('wizard-name').value.trim();
  const email = $('wizard-email').value.trim();
  if (!name || !email) {
    $('wizard-hint').textContent = 'Please add your name and email, then try again.';
    $(name ? 'wizard-email' : 'wizard-name').focus();
    return;
  }
  const button = $('wizard-create');
  button.disabled = true;
  button.textContent = 'Making your key…';
  try {
    const created = await invoke('create_identity', {
      name,
      email,
      password: $('wizard-password').value,
    });
    $('wizard-password').value = '';
    const { key } = await tryLoadKey();
    wizardKey = key || created;
    showWizardStep(2);
  } catch (error) {
    showError(error);
    $('wizard-hint').textContent = 'We could not make your key. Check the details and try again.';
  } finally {
    button.disabled = false;
    button.textContent = 'Create my key';
  }
});

$('wizard-copy').addEventListener('click', async () => {
  const key = wizardKey || (await tryLoadKey()).key;
  if (!key || !key.publicKey) {
    showError('We could not find your public key. Please try again.');
    return;
  }
  const copied = await copyText(key.publicKey);
  if (copied) toast('Public key copied. You can paste it anywhere.');
  else showError('Could not copy. Please try again, or copy it from the Keys & people page.', { banner: false });
});

$('wizard-next').addEventListener('click', () => showWizardStep(3));
$('wizard-example').addEventListener('click', () => finishWizard(true));
$('wizard-start').addEventListener('click', () => finishWizard(false));

$('wizard-already').addEventListener('click', async () => {
  const button = $('wizard-already');
  button.disabled = true;
  try {
    const { key, error } = await tryLoadKey();
    if (key) {
      wizardKey = key;
      finishWizard(false);
      toast('Welcome back. Your key is ready.');
    } else {
      if (error && !isNoKeyError(error)) showError(error);
      $('wizard-hint').textContent = 'We still could not find a key. You can make one here, or try again.';
      toast('We still could not find a key. Try again.', true);
    }
  } finally {
    button.disabled = false;
  }
});

// Enter advances the current step (buttons keep their normal Enter behaviour).
$('wizard').addEventListener('keydown', (event) => {
  if (event.key !== 'Enter' || event.shiftKey) return;
  if (['BUTTON', 'A', 'TEXTAREA'].includes(event.target.tagName)) return;
  event.preventDefault();
  if (wizardStep === 1) $('wizard-create').click();
  else if (wizardStep === 2) showWizardStep(3);
  else finishWizard(false);
});

$('help-revisit').addEventListener('click', async () => {
  const { key, error } = await tryLoadKey();
  if (error && !isNoKeyError(error)) showError(error);
  wizardKey = key;
  openWizard(key ? 2 : 1);
});

// ---------------------------------------------------------------------------
// Existing commands, kept working. User data is inserted with textContent.
// ---------------------------------------------------------------------------

$('create-btn').addEventListener('click', async () => {
  try {
    const result = await invoke('create_identity', {
      name: $('new-name').value,
      email: $('new-email').value,
      password: $('new-password').value,
    });
    toast(`Your key is ready. Fingerprint: ${result.fingerprint}`);
    $('new-password').value = '';
    const { key } = await tryLoadKey();
    if (key) wizardKey = key;
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
  const copied = await copyText($('compose-output').value);
  if (copied) toast('Copied to clipboard.');
  else showError('Could not copy — select the text and copy manually.', { banner: false });
});

$('decrypt-btn').addEventListener('click', async () => {
  try {
    const result = await invoke('decrypt_message', { armored: $('decrypt-input').value });
    const node = $('decrypt-output');
    node.textContent = '';
    const heading = document.createElement('h2');
    heading.textContent = 'Decrypted';
    node.appendChild(heading);
    const pre = document.createElement('pre');
    pre.textContent = String(result.text || '');
    node.appendChild(pre);
    const signature = document.createElement('p');
    const label = document.createElement('strong');
    label.textContent = 'Signature:';
    signature.appendChild(label);
    signature.appendChild(document.createTextNode(` ${result.signature || ''}`));
    node.appendChild(signature);
  } catch (error) { showError(error); }
});

// First load: a key means normal app; no key means the three-step welcome card.
(async function init() {
  const { key, error } = await tryLoadKey();
  wizardKey = key;
  if (key) closeWizard();
  else {
    if (error && !isNoKeyError(error)) showError(error);
    openWizard(1);
  }
  refreshContacts();
})();
