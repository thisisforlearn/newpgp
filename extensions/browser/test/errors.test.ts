// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Vaibhav - https://github.com/thisisforlearn
import { describe, expect, it } from 'vitest';
import { bridgeError, friendlyMessage, toFriendlyError } from '../src/native/errors';
import { MockBridge } from '../src/native/MockBridge';

describe('friendly error mapping', () => {
  it('maps a missing native host to an installation hint', () => {
    const friendly = toFriendlyError(bridgeError('NATIVE_HOST_NOT_FOUND'));
    expect(friendly.title).toMatch(/helper/i);
    expect(friendly.hint).toMatch(/install/i);
    expect(friendlyMessage(bridgeError('NATIVE_HOST_NOT_FOUND'))).not.toContain('NATIVE_HOST_NOT_FOUND');
  });

  it('maps no-key, invalid armor and disconnect without leaking codes', () => {
    expect(toFriendlyError(bridgeError('NO_KEY')).message).toMatch(/public key/i);
    expect(toFriendlyError(bridgeError('INVALID_ARMOR')).title).toMatch(/PGP block/i);
    expect(toFriendlyError(bridgeError('BRIDGE_DISCONNECTED')).title).toMatch(/disconnected/i);
    for (const code of ['NO_KEY', 'INVALID_ARMOR', 'BRIDGE_DISCONNECTED'] as const) {
      expect(friendlyMessage(bridgeError(code))).not.toContain(code);
    }
  });

  it('recognizes raw native messaging errors', () => {
    const friendly = toFriendlyError(new Error('Specified native messaging host com.newpgp.bridge not found.'));
    expect(friendly.title).toMatch(/helper/i);
    expect(toFriendlyError(new Error('Port closed')).title).toMatch(/disconnected/i);
    expect(toFriendlyError(new Error('Request timed out')).title).toMatch(/too long/i);
  });

  it('never shows an empty or cryptic message for unknown failures', () => {
    const friendly = toFriendlyError({ weird: true });
    expect(friendly.title.length).toBeGreaterThan(0);
    expect(friendly.message.length).toBeGreaterThan(0);
    expect(friendly.hint).toBeTruthy();
  });

  it('maps MockBridge failures through the same friendly layer', async () => {
    const bridge = new MockBridge({ failures: { discoverKey: 'NO_KEY' } });
    let thrown: unknown;
    try {
      await bridge.discoverKey({ email: 'nobody@example.com' });
    } catch (error) {
      thrown = error;
    }
    expect(toFriendlyError(thrown).title).toMatch(/key/i);
  });
});
