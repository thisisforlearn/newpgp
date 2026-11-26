// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Vaibhav - https://github.com/thisisforlearn
/** Popup: first-run setup wizard, send-mode switch, key discovery and status. */

import { DEFAULT_SETTINGS, isSetupComplete, markSetupComplete } from '../composition/settings';
import { describeMode } from '../composition/compose';
import { callBackground, fail, ok, type RuntimeResult } from '../messages';
import { NativeMessagingClient } from '../native/NativeMessagingClient';
import { toFriendlyError, type FriendlyError } from '../native/errors';
import { getBrowserApi, type BrowserApi } from '../platform/browser';
import { el, requireElement } from './dom';
import type { DiscoveredKey, KeyPairResult, RuntimeMessage, Settings } from '../types';

const api = getBrowserApi();
const toggle = requireElement<HTMLInputElement>('#mode-toggle');
const modeDescription = requireElement<HTMLElement>('#mode-description');
const modeTradeoff = requireElement<HTMLElement>('#mode-tradeoff');
const status = requireElement<HTMLElement>('#bridge-status');
const settingsError = requireElement<HTMLElement>('#settings-error');
const form = requireElement<HTMLFormElement>('#discover-form');
const emailInput = requireElement<HTMLInputElement>('#email');
const result = requireElement<HTMLElement>('#key-result');

const setupWizard = requireElement<HTMLElement>('#setup-wizard');
const setupStepLabel = requireElement<HTMLElement>('#setup-step-label');
const setupFeedback = requireElement<HTMLElement>('#setup-feedback');
const setupPanels = {
  1: requireElement<HTMLElement>('#setup-step-1'),
  2: requireElement<HTMLElement>('#setup-step-2'),
  3: requireElement<HTMLElement>('#setup-step-3'),
};
const pingButton = requireElement<HTMLButtonElement>('#setup-ping-button');
const keyForm = requireElement<HTMLFormElement>('#setup-key-form');
const setupName = requireElement<HTMLInputElement>('#setup-name');
const setupEmail = requireElement<HTMLInputElement>('#setup-email');
const createKeyButton = requireElement<HTMLButtonElement>('#setup-create-key');
const doneLinks = [
  requireElement<HTMLAnchorElement>('#setup-done-gmail'),
  requireElement<HTMLAnchorElement>('#setup-done-outlook'),
];

let settings: Settings = { ...DEFAULT_SETTINGS };

/* -------------------------------------------------------------------------- */
/* Setup wizard state machine                                                 */
/* -------------------------------------------------------------------------- */

export type SetupStep = 1 | 2 | 3 | 'done';

export interface SetupNotice {
  title: string;
  message: string;
  hint?: string;
}

export interface SetupState {
  step: SetupStep;
  connected: boolean;
  fingerprint: string | null;
  error: SetupNotice | null;
}

export type SetupEvent =
  | { type: 'PING_SUCCEEDED' }
  | { type: 'PING_FAILED'; notice: SetupNotice }
  | { type: 'KEY_CREATED'; fingerprint: string }
  | { type: 'KEY_FAILED'; notice: SetupNotice }
  | { type: 'FINISHED' }
  | { type: 'RESTORE_COMPLETED' }
  | { type: 'RESET' };

export const INITIAL_SETUP_STATE: SetupState = {
  step: 1,
  connected: false,
  fingerprint: null,
  error: null,
};

/**
 * A step never advances on a guess: only a successful ping or a key with a
 * fingerprint moves the wizard forward. Errors keep the user on the same step.
 */
export function reduceSetup(state: SetupState, event: SetupEvent): SetupState {
  switch (event.type) {
    case 'PING_SUCCEEDED':
      return state.step === 1 ? { ...state, step: 2, connected: true, error: null } : state;
    case 'PING_FAILED':
      return state.step === 1 ? { ...state, error: event.notice } : state;
    case 'KEY_CREATED':
      return state.step === 2 && event.fingerprint.trim() !== ''
        ? { ...state, step: 3, fingerprint: event.fingerprint.trim(), error: null }
        : state;
    case 'KEY_FAILED':
      return state.step === 2 ? { ...state, error: event.notice } : state;
    case 'FINISHED':
      return state.step === 3 ? { ...state, step: 'done', error: null } : state;
    case 'RESTORE_COMPLETED':
      return { ...state, step: 'done', error: null };
    case 'RESET':
      return { ...INITIAL_SETUP_STATE };
  }
}

/**
 * Wizard hints are written here rather than reusing generic backend hints:
 * those older hints mention a desktop app/daemon that this extension does not
 * ship. The helper is started by the browser when we ask for it.
 */
const SETUP_HINT =
  'Open the installer link above, use your exact extension ID, restart your browser, then reload NewPGP.';

export function pingFailureNotice(error?: FriendlyError): SetupNotice {
  return {
    title: 'Not yet — try these steps',
    message: error?.message ?? 'We could not reach the helper on this computer.',
    hint: SETUP_HINT,
  };
}

export function keyFailureNotice(error?: FriendlyError): SetupNotice {
  return {
    title: "We couldn't make your key yet",
    message: error?.message ?? 'The helper did not finish making a key.',
    hint: SETUP_HINT,
  };
}

/**
 * Ask the shared background router to create the key. The router shipped
 * before key creation existed, so workers without that case answer
 * "Unknown request"; in that case we open the nativeMessaging port from this
 * extension page instead. Only a name and email cross the boundary, and the
 * private key is never returned to or stored by the extension.
 */
export async function createSetupKey(
  api: BrowserApi,
  request: { name: string; email: string },
): Promise<RuntimeResult<KeyPairResult>> {
  const routed = await callBackground<KeyPairResult>(api, {
    type: 'GENERATE_KEY',
    name: request.name,
    email: request.email,
  } as unknown as RuntimeMessage);
  if (routed.ok || routed.error?.title !== 'Unknown request') return routed;

  const bridge = new NativeMessagingClient(api);
  try {
    return ok(await bridge.generateKey({ name: request.name, email: request.email }));
  } catch (error) {
    return fail(toFriendlyError(error));
  } finally {
    bridge.close();
  }
}

let setup: SetupState = { ...INITIAL_SETUP_STATE };

function renderNotice(notice: SetupNotice): void {
  const card = el('div', { className: 'notice wizard__notice notice--error' }, [
    el('strong', { className: 'wizard__notice-title', text: notice.title }),
    el('p', { text: notice.message }),
  ]);
  if (notice.hint) card.append(el('p', { className: 'hint', text: notice.hint }));
  setupFeedback.replaceChildren(card);
}

function renderSuccess(title: string, fingerprint?: string): void {
  const card = el('div', { className: 'notice wizard__notice notice--good' }, [
    el('strong', { className: 'wizard__notice-title', text: title }),
  ]);
  if (fingerprint !== undefined) {
    card.append(
      el('code', {
        className: 'fingerprint fingerprint--large',
        text: fingerprint.replace(/(.{4})/g, '$1 ').trim(),
      }),
    );
  }
  setupFeedback.replaceChildren(card);
}

function renderSetup(state: SetupState): void {
  setupWizard.hidden = state.step === 'done';
  for (const step of [1, 2, 3] as const) setupPanels[step].hidden = state.step !== step;
  setupStepLabel.textContent = state.step === 'done' ? 'Setup complete' : `Step ${state.step} of 3`;
  document.body.classList.toggle('showing-setup', state.step === 3);

  if (state.error && (state.step === 1 || state.step === 2)) {
    renderNotice(state.error);
  } else if (state.fingerprint) {
    renderSuccess('Your key is ready', state.fingerprint);
  } else if (state.connected) {
    renderSuccess('Connected');
  } else {
    setupFeedback.replaceChildren();
  }

  pingButton.disabled = state.step !== 1;
}

function dispatch(event: SetupEvent): void {
  setup = reduceSetup(setup, event);
  renderSetup(setup);
}

/* -------------------------------------------------------------------------- */
/* Ordinary popup behaviour                                                   */
/* -------------------------------------------------------------------------- */

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

  if (await isSetupComplete(api)) {
    setup = reduceSetup(setup, { type: 'RESTORE_COMPLETED' });
  }
  renderSetup(setup);
}

/* -------------------------------------------------------------------------- */
/* Wiring                                                                     */
/* -------------------------------------------------------------------------- */

pingButton.addEventListener('click', async () => {
  pingButton.disabled = true;
  const response = await callBackground<{ connected: boolean }>(api, { type: 'PING' });
  if (response.ok) {
    dispatch({ type: 'PING_SUCCEEDED' });
    return;
  }
  dispatch({ type: 'PING_FAILED', notice: pingFailureNotice(response.error) });
});

keyForm.addEventListener('submit', (event) => {
  event.preventDefault();
  const name = setupName.value.trim();
  const email = setupEmail.value.trim();
  if (!name || !/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email)) {
    dispatch({
      type: 'KEY_FAILED',
      notice: {
        title: 'Please check your details',
        message: 'Enter your name and a complete email address.',
        hint: 'For example, alex@example.com.',
      },
    });
    return;
  }

  createKeyButton.disabled = true;
  void createSetupKey(api, { name, email }).then((response) => {
    if (response.ok && response.result?.fingerprint) {
      dispatch({ type: 'KEY_CREATED', fingerprint: response.result.fingerprint });
      return;
    }
    createKeyButton.disabled = false;
    dispatch({ type: 'KEY_FAILED', notice: keyFailureNotice(response.error) });
  });
});

for (const link of doneLinks) {
  link.addEventListener('click', (event) => {
    event.preventDefault();
    window.open(link.href, '_blank', 'noopener');
    dispatch({ type: 'FINISHED' });
    void markSetupComplete(api).catch(() => {
      settingsError.textContent = 'We could not remember that setup is finished. It will show again next time.';
    });
  });
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
