// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Vaibhav - https://github.com/thisisforlearn
/** Popup: send-mode switch, key discovery, and a plain-language status. */

import { DEFAULT_SETTINGS } from '../composition/settings';
import { describeMode } from '../composition/compose';
import { callBackground } from '../messages';
import { getBrowserApi } from '../platform/browser';
import { el, requireElement } from './dom';
import type { DiscoveredKey, Settings } from '../types';

const api = getBrowserApi();
const toggle = requireElement<HTMLInputElement>('#mode-toggle');
const modeDescription = requireElement<HTMLElement>('#mode-description');
const modeTradeoff = requireElement<HTMLElement>('#mode-tradeoff');
const status = requireElement<HTMLElement>('#bridge-status');
const settingsError = requireElement<HTMLElement>('#settings-error');
const form = requireElement<HTMLFormElement>('#discover-form');
const emailInput = requireElement<HTMLInputElement>('#email');
const result = requireElement<HTMLElement>('#key-result');

let settings: Settings = { ...DEFAULT_SETTINGS };

function applyMode(next: Settings): void {
  settings = next;
  toggle.checked = next.sendMode === 'pgp-only';
  const copy = describeMode(next.sendMode);
  modeDescription.textContent = copy.description;
  modeTradeoff.textContent =
    next.sendMode === 'pgp-only'
      ? 'Heads up: non-PGP recipients will only see an encrypted block and cannot read your message.'
      : 'Tradeoff: anyone with access to the email can read the plain text. Use PGP only for sensitive mail.';
}

function renderKey(key: DiscoveredKey): void {
  result.replaceChildren();
  const card = el('div', { className: 'key-card' }, [
    el('div', { className: 'key-card__title', text: 'We found a public key' }),
    el('code', {
      className: 'fingerprint',
      text: key.fingerprint.replace(/(.{4})/g, '$1 ').trim(),
    }),
    el('div', { className: 'safety-number', text: `Safety number: ${key.safetyNumber}` }),
    el('p', {
      className: 'hint',
      text:
        'This short number is an easier way to recognise the key. Compare it with your contact by phone, in person, or in another chat before you trust it.',
    }),
    el('p', { className: 'source', text: `Found through ${key.source}.` }),
  ]);
  result.append(card);
}

function renderError(title: string, message: string, hint?: string): void {
  result.replaceChildren();
  const card = el('div', { className: 'notice notice--error' }, [
    el('strong', { text: title }),
    el('p', { text: message }),
  ]);
  if (hint) card.append(el('p', { className: 'hint', text: hint }));
  result.append(card);
}

async function init(): Promise<void> {
  const loaded = await callBackground<Settings>(api, { type: 'GET_SETTINGS' });
  applyMode(loaded.ok && loaded.result ? loaded.result : { ...DEFAULT_SETTINGS });

  const ping = await callBackground<{ connected: boolean }>(api, { type: 'PING' });
  status.textContent = ping.ok ? 'Helper connected' : 'Helper not found';
  status.classList.toggle('pill--error', !ping.ok);
  if (!ping.ok && ping.error) {
    status.title = `${ping.error.title} ${ping.error.message}`;
  }
}

toggle.addEventListener('change', async () => {
  const previous = settings;
  const next: Settings = {
    ...settings,
    sendMode: toggle.checked ? 'pgp-only' : 'send-both',
  };
  applyMode(next);
  settingsError.textContent = '';
  const response = await callBackground(api, { type: 'SET_SETTINGS', settings: next });
  if (!response.ok) {
    // Never leave the UI claiming a mode that was not actually saved.
    applyMode(previous);
    const friendly = response.error;
    settingsError.textContent = `Could not save that setting. ${
      friendly ? `${friendly.title} ${friendly.message}` : 'Please try again.'
    } Your previous choice was restored.`;
  }
});

form.addEventListener('submit', (event) => {
  event.preventDefault();
  const email = emailInput.value.trim();
  result.replaceChildren(el('p', { className: 'hint', text: 'Looking for a key…' }));
  void callBackground<DiscoveredKey>(api, { type: 'DISCOVER_KEY', email }).then((response) => {
    if (response.ok && response.result) {
      renderKey(response.result);
      return;
    }
    const friendly = response.error;
    renderError(
      friendly?.title ?? 'We could not find a key',
      friendly?.message ?? 'Please try again in a moment.',
      friendly?.hint,
    );
  });
});

void init();
