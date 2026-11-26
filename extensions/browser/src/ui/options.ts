// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Vaibhav - https://github.com/thisisforlearn
/** Options page: defaults, privacy notes and the native-host setup guide. */

import { DEFAULT_SETTINGS } from '../composition/settings';
import { describeMode } from '../composition/compose';
import { callBackground } from '../messages';
import { getBrowserApi } from '../platform/browser';
import { requireElement } from './dom';
import type { Settings } from '../types';

const api = getBrowserApi();
const modeToggle = requireElement<HTMLInputElement>('#mode-toggle');
const enabledToggle = requireElement<HTMLInputElement>('#enabled-toggle');
const autosaveToggle = requireElement<HTMLInputElement>('#autosave-toggle');
const modeDescription = requireElement<HTMLElement>('#mode-description');
const saved = requireElement<HTMLElement>('#saved-status');
const settingsError = requireElement<HTMLElement>('#settings-error');

let settings: Settings = { ...DEFAULT_SETTINGS };

function apply(next: Settings): void {
  settings = next;
  modeToggle.checked = next.sendMode === 'pgp-only';
  enabledToggle.checked = next.enabled;
  autosaveToggle.checked = next.autosaveAcknowledged;
  modeDescription.textContent = describeMode(next.sendMode).description;
}

async function persist(next: Settings): Promise<void> {
  const previous = settings;
  apply(next);
  settingsError.textContent = '';
  const response = await callBackground(api, { type: 'SET_SETTINGS', settings: next });
  if (!response.ok) {
    apply(previous);
    const friendly = response.error;
    settingsError.textContent = `Could not save that setting. ${
      friendly ? `${friendly.title} ${friendly.message}` : 'Please try again.'
    } Your previous choice was restored.`;
    saved.textContent = 'Could not save settings';
    return;
  }
  saved.textContent = 'Saved';
}

modeToggle.addEventListener('change', () => {
  void persist({ ...settings, sendMode: modeToggle.checked ? 'pgp-only' : 'send-both' });
});

enabledToggle.addEventListener('change', () => {
  void persist({ ...settings, enabled: enabledToggle.checked });
});

autosaveToggle.addEventListener('change', () => {
  void persist({ ...settings, autosaveAcknowledged: autosaveToggle.checked });
});

void callBackground<Settings>(api, { type: 'GET_SETTINGS' }).then((response) => {
  apply(response.ok && response.result ? response.result : { ...DEFAULT_SETTINGS });
});
