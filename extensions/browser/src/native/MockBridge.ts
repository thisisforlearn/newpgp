// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Vaibhav - https://github.com/thisisforlearn
/**
 * In-memory Bridge implementation for tests and for running the UI without
 * the native host. It produces real-looking armored blocks, deterministic
 * fingerprints/safety numbers, and can be configured to fail in specific
 * ways. It is explicitly a *mock*: no cryptography is performed here.
 */

import { base64Decode, base64Encode } from '../crypto/base64';
import { armorBody, wrapBase64 } from '../crypto/normalizeArmor';
import { safetyNumberFromFingerprint } from '../crypto/safetyNumber';
import { bridgeError } from './errors';
import type {
  Bridge,
  BridgeErrorCode,
  BridgeMethod,
  DecryptParams,
  DecryptResult,
  DiscoverKeyParams,
  DiscoveredKey,
  EncryptParams,
  EncryptResult,
  GenerateKeyParams,
  GetKeyParams,
  KeyInfo,
  KeyPairResult,
  SignParams,
  SignResult,
  SignatureStatus,
  VerifyParams,
  VerifyResult,
} from '../types';

export interface MockBridgeOptions {
  /** email (lowercase) -> published key */
  knownKeys?: Record<string, DiscoveredKey>;
  /** Signature status returned by decrypt/verify. Defaults to `verified`. */
  verification?: SignatureStatus;
  /** Method -> error code to throw, for testing friendly errors. */
  failures?: Partial<Record<BridgeMethod, BridgeErrorCode>>;
  /** Require recipients to have published keys before encrypting. */
  enforceRecipientKeys?: boolean;
}

function fingerprintFor(email: string): string {
  let hash = 0x9e3779b9;
  for (const char of email.toLowerCase()) {
    hash ^= char.charCodeAt(0);
    hash = Math.imul(hash, 0x85ebca6b);
  }
  const base = (hash >>> 0).toString(16).padStart(8, '0').toUpperCase();
  return (base.repeat(5) + base).slice(0, 40);
}

export function mockKey(email: string, source: DiscoveredKey['source'] = 'WKD'): DiscoveredKey {
  const normalized = email.trim().toLowerCase();
  return {
    email: normalized,
    fingerprint: fingerprintFor(normalized),
    safetyNumber: safetyNumberFromFingerprint(fingerprintFor(normalized)),
    source,
    confidence: 'high',
  };
}

interface MockPayload {
  plaintext: string;
  recipients: string[];
  signed: boolean;
}

export class MockBridge implements Bridge {
  private readonly keys: Record<string, DiscoveredKey>;
  private readonly verification: SignatureStatus;
  private readonly failures: Partial<Record<BridgeMethod, BridgeErrorCode>>;
  private readonly enforceRecipientKeys: boolean;

  constructor(options: MockBridgeOptions = {}) {
    this.keys = { ...(options.knownKeys ?? {}) };
    this.verification = options.verification ?? 'verified';
    this.failures = { ...(options.failures ?? {}) };
    this.enforceRecipientKeys = options.enforceRecipientKeys ?? false;
  }

  private guard(method: BridgeMethod): void {
    const failure = this.failures[method];
    if (failure) throw bridgeError(failure, `Mock failure: ${failure}`);
  }

  async generateKey(params: GenerateKeyParams): Promise<KeyPairResult> {
    this.guard('generateKey');
    const fingerprint = fingerprintFor(params.email);
    return {
      fingerprint,
      publicKey: `-----BEGIN PGP PUBLIC KEY BLOCK-----\nVersion: NewPGP Mock\n\n${wrapBase64(base64Encode(fingerprint)).join('\n')}\n-----END PGP PUBLIC KEY BLOCK-----`,
      privateKey: 'MOCK-PRIVATE-KEY-NOT-FOR-REAL-USE',
      safetyNumber: safetyNumberFromFingerprint(fingerprint),
    };
  }

  async encrypt(params: EncryptParams): Promise<EncryptResult> {
    this.guard('encrypt');
    const recipients = params.recipients.map((value) => value.trim().toLowerCase()).filter(Boolean);
    if (this.enforceRecipientKeys) {
      for (const email of recipients) {
        if (!this.keys[email]) throw bridgeError('NO_KEY', `No key for ${email}`);
      }
    }
    const payload: MockPayload = {
      plaintext: params.plaintext,
      recipients,
      signed: params.sign === true,
    };
    const body = wrapBase64(base64Encode(JSON.stringify(payload))).join('\n');
    return {
      armored: `-----BEGIN PGP MESSAGE-----\nVersion: NewPGP Mock 0.1\n\n${body}\n-----END PGP MESSAGE-----`,
      fingerprint: fingerprintFor(recipients.join(',') || 'self'),
    };
  }

  async decrypt(params: DecryptParams): Promise<DecryptResult> {
    this.guard('decrypt');
    const body = armorBody(params.armored);
    if (body.length === 0) throw bridgeError('INVALID_ARMOR', 'No armored body found.');
    let payload: MockPayload;
    try {
      payload = JSON.parse(base64Decode(body)) as MockPayload;
    } catch {
      throw bridgeError('DECRYPT_FAILED', 'Mock bridge could not decode this block.');
    }
    if (typeof payload?.plaintext !== 'string') {
      throw bridgeError('DECRYPT_FAILED', 'Mock block had no plaintext.');
    }
    return {
      plaintext: payload.plaintext,
      signature: params.verify === false ? 'unverified' : this.verification,
    };
  }

  async sign(params: SignParams): Promise<SignResult> {
    this.guard('sign');
    const body = wrapBase64(base64Encode(params.data)).join('\n');
    return {
      armored: `-----BEGIN PGP SIGNATURE-----\nVersion: NewPGP Mock 0.1\n\n${body}\n-----END PGP SIGNATURE-----`,
    };
  }

  async verify(_params: VerifyParams): Promise<VerifyResult> {
    this.guard('verify');
    return {
      valid: this.verification === 'verified',
      signerFingerprint: this.verification === 'verified' ? fingerprintFor('signer@newpgp.local') : undefined,
    };
  }

  async getKey(params: GetKeyParams): Promise<KeyInfo> {
    this.guard('getKey');
    const email = (params.email ?? '').trim().toLowerCase();
    const fingerprint = params.fingerprint?.replace(/\s/g, '').toUpperCase() ?? this.keys[email]?.fingerprint;
    if (!fingerprint) throw bridgeError('NO_KEY', 'No matching key.');
    return {
      fingerprint,
      email: email || undefined,
      safetyNumber: safetyNumberFromFingerprint(fingerprint),
    };
  }

  async discoverKey(params: DiscoverKeyParams): Promise<DiscoveredKey> {
    this.guard('discoverKey');
    const email = params.email.trim().toLowerCase();
    const key = this.keys[email];
    if (!key) throw bridgeError('NO_KEY', `No key found for ${email}`);
    return { ...key };
  }

  async ping(): Promise<void> {
    // The mock is always reachable unless a specific method was configured to
    // fail; ping itself never carries user data.
  }

  close(): void {
    // Nothing to release in the mock.
  }
}
