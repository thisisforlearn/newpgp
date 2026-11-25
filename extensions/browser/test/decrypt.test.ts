// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Vaibhav - https://github.com/thisisforlearn
import { describe, expect, it, vi } from 'vitest';
import { normalizeArmor } from '../src/crypto/normalizeArmor';
import { buildDecryptView, renderDecryptError, renderDecryptedView } from '../src/mail/decryptView';
import { findArmorTextNodes } from '../src/content/incoming';
import { MockBridge } from '../src/native/MockBridge';
import { toFriendlyError, bridgeError } from '../src/native/errors';
import type { DecryptResult } from '../src/types';

const ARMOR = ['-----BEGIN PGP MESSAGE-----', '', 'QUJDRA==', '-----END PGP MESSAGE-----'].join('\n');

describe('decrypt view', () => {
  it('labels a verified signature clearly', () => {
    const view = buildDecryptView({ plaintext: 'Hello', signature: 'verified' });
    expect(view.signatureLabel).toBe('Verified');
    expect(view.tone).toBe('good');
  });

  it('uses the plain-language label for unverified signatures', () => {
    const view = buildDecryptView({ plaintext: 'Hello', signature: 'unverified' });
    expect(view.signatureLabel).toBe('Unverified — verify by comparing the safety number');
    expect(view.hint).toMatch(/safety number/);
  });

  it('flags invalid signatures', () => {
    const view = buildDecryptView({ plaintext: 'Hello', signature: 'invalid' });
    expect(view.signatureLabel).toBe('Invalid');
    expect(view.tone).toBe('bad');
  });

  it('renders decrypted text with textContent so mail cannot inject HTML', () => {
    const container = document.createElement('div');
    const result: DecryptResult = {
      plaintext: '<img src=x onerror="alert(1)">',
      signature: 'unverified',
    };
    renderDecryptedView(container, buildDecryptView(result));
    expect(container.querySelector('img')).toBeNull();
    expect(container.querySelector('.newpgp-decrypted__text')?.textContent).toBe(result.plaintext);
    expect(container.querySelector('.newpgp-status--warn')?.textContent).toContain('Unverified');
    expect(container.getAttribute('data-newpgp-armor')).toBe('done');
  });

  it('renders friendly errors with a retry button', () => {
    const container = document.createElement('div');
    const retry = vi.fn();
    renderDecryptError(container, toFriendlyError(bridgeError('DECRYPT_FAILED')), retry);
    expect(container.textContent).toMatch(/decrypt/i);
    const button = container.querySelector('button');
    expect(button?.textContent).toBe('Try again');
    button?.dispatchEvent(new MouseEvent('click'));
    expect(retry).toHaveBeenCalledOnce();
  });
});

describe('incoming armor detection', () => {
  it('finds armored blocks in a mail body and ignores processed ones', () => {
    const body = document.createElement('div');
    body.append(document.createTextNode(`Here: ${ARMOR}`));
    const processed = document.createElement('div');
    processed.setAttribute('data-newpgp-armor', 'done');
    processed.textContent = ARMOR;
    body.append(processed);
    document.body.append(body);

    const nodes = findArmorTextNodes(document.body);
    expect(nodes).toHaveLength(1);
    expect(nodes[0]?.textContent).toContain('BEGIN PGP MESSAGE');
  });

  it('decrypts mangled armor with the MockBridge and shows the signature status', async () => {
    const bridge = new MockBridge({ verification: 'verified' });
    const encrypted = await bridge.encrypt({ plaintext: 'Hello from a quoted email', recipients: [] });
    const mangled = `> ${encrypted.armored.replace(/\n/g, '\n> ')}`;
    const normalized = normalizeArmor(mangled);
    expect(normalized.found).toBe(true);
    const result = await bridge.decrypt({ armored: normalized.armored, verify: true });
    expect(result.plaintext).toBe('Hello from a quoted email');
    const view = buildDecryptView(result);
    const container = document.createElement('div');
    renderDecryptedView(container, view);
    expect(container.querySelector('.newpgp-status--good')?.textContent).toBe('Verified');
  });
});
