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

let settings: Settings = { ...DEFAULT_SETTINGS };

function apply(next: Settings): void {
  settings = next;
  modeToggle.checked = next.sendMode === 'pgp-only';
  enabledToggle.checked = next.enabled;
  autosaveToggle.checked = next.autosaveAcknowledged;
  modeDescription.textContent = describeMode(next.sendMode).description;
}

function persist(): void {
  void callBackground(api, { type: 'SET_SETTINGS', settings }).then((response) => {
    saved.textContent = response.ok ? 'Saved' : 'Could not save settings';
  });
}

modeToggle.addEventListener('change', () => {
  apply({ ...settings, sendMode: modeToggle.checked ? 'pgp-only' : 'send-both' });
  persist();
});

enabledToggle.addEventListener('change', () => {
  apply({ ...settings, enabled: enabledToggle.checked });
  persist();
});

autosaveToggle.addEventListener('change', () => {
  apply({ ...settings, autosaveAcknowledged: autosaveToggle.checked });
  persist();
});

void callBackground<Settings>(api, { type: 'GET_SETTINGS' }).then((response) => {
  apply(response.ok && response.result ? response.result : { ...DEFAULT_SETTINGS });
});
