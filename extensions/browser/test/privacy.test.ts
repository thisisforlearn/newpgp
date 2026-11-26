// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Vaibhav - https://github.com/thisisforlearn
/**
 * Privacy-critical invariants: the extension stores settings only, and a
 * storage failure must never silently relax a PGP-only preference.
 */
import { describe, expect, it } from 'vitest';
import {
  DEFAULT_SETTINGS,
  SETTINGS_KEY,
  loadSettings,
  resetSettingsCache,
  sanitizeSettings,
  saveSettings,
} from '../src/composition/settings';
import { createMockBrowserApi } from './mocks/browser';

describe('privacy-critical settings', () => {
  it('defaults to the readable send-both mode', () => {
    expect(DEFAULT_SETTINGS.sendMode).toBe('send-both');
    expect(DEFAULT_SETTINGS.enabled).toBe(true);
  });

  it('sanitizes away message text, keys and unknown fields', () => {
    const dirty = {
      ...DEFAULT_SETTINGS,
      sendMode: 'pgp-only',
      plaintext: 'Secret meeting at 10',
      keyMaterial: '-----BEGIN PGP PRIVATE KEY BLOCK-----',
      decryptedText: 'also secret',
    };
    const clean = sanitizeSettings(dirty);
    expect(clean).toEqual({ ...DEFAULT_SETTINGS, sendMode: 'pgp-only' });
    expect(JSON.stringify(clean)).not.toMatch(/Secret|PRIVATE KEY|secret/);
  });

  it('writes only sanitized settings to browser storage', async () => {
    const api = createMockBrowserApi();
    await saveSettings(api, {
      ...DEFAULT_SETTINGS,
      plaintext: 'TOP SECRET DRAFT',
    } as Parameters<typeof saveSettings>[1]);
    expect(JSON.stringify(api.store)).not.toContain('TOP SECRET DRAFT');
    expect(Object.keys(api.store)).toEqual([SETTINGS_KEY]);
    expect(api.store[SETTINGS_KEY]).toEqual(DEFAULT_SETTINGS);
  });

  it('keeps the last known PGP-only mode when storage fails', async () => {
    const api = createMockBrowserApi();
    await saveSettings(api, { ...DEFAULT_SETTINGS, sendMode: 'pgp-only' });

    const broken = createMockBrowserApi();
    broken.storage.local.get = async () => {
      throw new Error('storage temporarily unavailable');
    };
    await expect(loadSettings(broken)).resolves.toMatchObject({ sendMode: 'pgp-only' });
  });

  it('falls back to the documented default only before any setting is known', async () => {
    resetSettingsCache();
    const fresh = createMockBrowserApi();
    fresh.storage.local.get = async () => {
      throw new Error('storage temporarily unavailable');
    };
    await expect(loadSettings(fresh)).resolves.toEqual(DEFAULT_SETTINGS);
  });
});
