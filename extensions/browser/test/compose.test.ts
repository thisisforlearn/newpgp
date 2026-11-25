// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Vaibhav - https://github.com/thisisforlearn
import { describe, expect, it } from 'vitest';
import { composeBody, describeMode } from '../src/composition/compose';
import { DEFAULT_SETTINGS, loadSettings, sanitizeSettings, saveSettings } from '../src/composition/settings';
import { MockBridge, mockKey } from '../src/native/MockBridge';
import { createMockBrowserApi } from './mocks/browser';

const ARMOR = ['-----BEGIN PGP MESSAGE-----', '', 'QUJDRA==', '-----END PGP MESSAGE-----'].join('\n');

describe('send-both composition', () => {
  it('appends armor without deleting the typed draft', () => {
    const draft = 'Hi Sam,\nLet us meet at 10.\nThanks!';
    const result = composeBody({ draft, armored: ARMOR, mode: 'send-both' });
    expect(result.applied).toBe(true);
    expect(result.body.startsWith(draft)).toBe(true);
    expect(result.body).toContain(ARMOR);
    expect(result.body.indexOf(ARMOR)).toBeGreaterThan(draft.length - 1);
  });

  it('does not add a second separator when the draft already ends with a blank line', () => {
    const result = composeBody({ draft: 'Hello\n\n', armored: ARMOR, mode: 'send-both' });
    expect(result.body).toBe(`Hello\n\n${ARMOR}`);
  });

  it('keeps the draft untouched when encryption produced no armor', () => {
    const draft = 'Important unsent words';
    const result = composeBody({ draft, armored: '   ', mode: 'send-both' });
    expect(result.applied).toBe(false);
    expect(result.body).toBe(draft);
  });

  it('PGP-only mode replaces the body with only the armored block', () => {
    const draft = 'Secret meeting at 10';
    const result = composeBody({ draft, armored: ARMOR, mode: 'pgp-only' });
    expect(result.applied).toBe(true);
    expect(result.body).toBe(ARMOR);
    expect(result.body).not.toContain('Secret');
  });

  it('PGP-only explains the tradeoff to non-PGP recipients', () => {
    expect(describeMode('pgp-only').description).toMatch(/do not use PGP/);
    expect(describeMode('send-both').label).toMatch(/recommended/i);
  });

  it('defaults to send-both and round-trips settings through storage', async () => {
    const api = createMockBrowserApi();
    expect(DEFAULT_SETTINGS.sendMode).toBe('send-both');
    expect((await loadSettings(api)).sendMode).toBe('send-both');
    await saveSettings(api, { ...DEFAULT_SETTINGS, sendMode: 'pgp-only' });
    expect((await loadSettings(api)).sendMode).toBe('pgp-only');
    expect(JSON.stringify(api.store)).not.toContain('Important unsent words');
  });

  it('sanitizes corrupt settings back to the safe default', () => {
    expect(sanitizeSettings({ sendMode: 'delete-everything', enabled: 'yes' })).toEqual(DEFAULT_SETTINGS);
  });

  it('works end to end with the MockBridge and a PGP-only toggle', async () => {
    const bridge = new MockBridge();
    draftFixture();
    const encrypted = await bridge.encrypt({ plaintext: 'Secret meeting at 10', recipients: ['friend@example.com'] });

    const sendBoth = composeBody({ draft: 'Secret meeting at 10', armored: encrypted.armored, mode: 'send-both' });
    expect(sendBoth.body).toContain('Secret meeting at 10');
    expect(sendBoth.body).toContain('-----BEGIN PGP MESSAGE-----');

    const onlyPgp = composeBody({ draft: 'Secret meeting at 10', armored: encrypted.armored, mode: 'pgp-only' });
    expect(onlyPgp.body).not.toContain('Secret meeting at 10');
    expect(onlyPgp.body).toContain('-----BEGIN PGP MESSAGE-----');
  });
});

function draftFixture(): void {
  // Documents that the draft lives only in the caller's memory: nothing is
  // written to browser storage by the composition pipeline.
  const api = createMockBrowserApi();
  expect(Object.keys(api.store)).toHaveLength(0);
}

describe('key discovery data', () => {
  it('produces a deterministic safety number from a fingerprint', async () => {
    const bridge = new MockBridge({ knownKeys: { 'friend@example.com': mockKey('friend@example.com') } });
    const first = await bridge.discoverKey({ email: 'friend@example.com' });
    const second = await bridge.discoverKey({ email: 'friend@example.com' });
    expect(first.fingerprint).toBe(second.fingerprint);
    expect(first.safetyNumber).toMatch(/^\d{4}( \d{4}){3}$/);
    expect(first.source).toBe('WKD');
  });
});
