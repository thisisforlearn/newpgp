// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Vaibhav - https://github.com/thisisforlearn
/**
 * Settings contain no message text, keys or plaintext. Composition drafts are
 * strictly in-memory in the content script.
 */

import type { BrowserApi } from '../platform/browser';
import type { Settings, SendMode } from '../types';

export const SETTINGS_KEY = 'newpgp.settings.v1';
/**
 * Separate first-run marker for the popup setup wizard. It holds only a
 * boolean-style flag: never message text, names, emails, keys or plaintext.
 */
export const SETUP_KEY = 'newpgp.setup.v1';

export interface SetupMarker {
  completed: boolean;
}

/** True only after the user finished the wizard. Storage failures show it again. */
export async function isSetupComplete(api: BrowserApi): Promise<boolean> {
  try {
    const stored = await api.storage.local.get(SETUP_KEY);
    return (stored[SETUP_KEY] as SetupMarker | undefined)?.completed === true;
  } catch {
    return false;
  }
}

export async function markSetupComplete(api: BrowserApi): Promise<void> {
  const marker: SetupMarker = { completed: true };
  await api.storage.local.set({ [SETUP_KEY]: marker });
}

export async function clearSetupComplete(api: BrowserApi): Promise<void> {
  await api.storage.local.remove(SETUP_KEY);
}

/**
 * Last settings we know were real. A transient storage failure must never
 * drop a PGP-only user back into plaintext-inclusive "send both" mode.
 * This cache lives in memory only; it holds no message text.
 */
let lastKnown: Settings | null = null;

/** For tests and for a clean worker start; never called in normal request flow. */
export function resetSettingsCache(): void {
  lastKnown = null;
}

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
    const clean = sanitizeSettings(stored[SETTINGS_KEY]);
    lastKnown = clean;
    return clean;
  } catch {
    // Storage is temporarily unavailable: keep the strictest preference we
    // last confirmed rather than reverting to the plaintext-inclusive default.
    return lastKnown ? { ...lastKnown } : { ...DEFAULT_SETTINGS };
  }
}

export async function saveSettings(api: BrowserApi, settings: Settings): Promise<void> {
  const clean = sanitizeSettings(settings);
  await api.storage.local.set({ [SETTINGS_KEY]: clean });
  lastKnown = clean;
}
