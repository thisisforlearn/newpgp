// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Vaibhav - https://github.com/thisisforlearn
/**
 * Settings contain no message text, keys or plaintext. Composition drafts are
 * strictly in-memory in the content script.
 */

import type { BrowserApi } from '../platform/browser';
import type { Settings, SendMode } from '../types';

export const SETTINGS_KEY = 'newpgp.settings.v1';

export const DEFAULT_SETTINGS: Settings = {
  sendMode: 'send-both',
  enabled: true,
  autosaveAcknowledged: false,
};

export function sanitizeSettings(value: unknown): Settings {
  if (typeof value !== 'object' || value === null) return { ...DEFAULT_SETTINGS };
  const candidate = value as Partial<Settings>;
  const sendMode: SendMode = candidate.sendMode === 'pgp-only' ? 'pgp-only' : 'send-both';
  return {
    sendMode,
    enabled: typeof candidate.enabled === 'boolean' ? candidate.enabled : DEFAULT_SETTINGS.enabled,
    autosaveAcknowledged:
      typeof candidate.autosaveAcknowledged === 'boolean'
        ? candidate.autosaveAcknowledged
        : DEFAULT_SETTINGS.autosaveAcknowledged,
  };
}

export async function loadSettings(api: BrowserApi): Promise<Settings> {
  try {
    const stored = await api.storage.local.get(SETTINGS_KEY);
    return sanitizeSettings(stored[SETTINGS_KEY]);
  } catch {
    return { ...DEFAULT_SETTINGS };
  }
}

export async function saveSettings(api: BrowserApi, settings: Settings): Promise<void> {
  const clean = sanitizeSettings(settings);
  await api.storage.local.set({ [SETTINGS_KEY]: clean });
}
