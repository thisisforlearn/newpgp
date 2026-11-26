// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Vaibhav - https://github.com/thisisforlearn
/**
 * Shared, typed contracts for the NewPGP browser extension.
 *
 * Everything crossing a boundary (popup <-> background, content script <->
 * background, background <-> native host) is described here so that a typo in
 * a method name or payload fails at compile time rather than at runtime.
 */

export type BridgeMethod =
  | 'generateKey'
  | 'encrypt'
  | 'decrypt'
  | 'sign'
  | 'verify'
  | 'getKey'
  | 'discoverKey';

/** Wire request sent to the native host. `id` correlates responses. */
export interface BridgeRequest<M extends BridgeMethod = BridgeMethod, P = Record<string, unknown>> {
  id: string;
  method: M;
  params: P;
}

export type BridgeErrorCode =
  | 'NATIVE_HOST_NOT_FOUND'
  | 'BRIDGE_DISCONNECTED'
  | 'BAD_REQUEST'
  | 'NO_KEY'
  | 'AMBIGUOUS_KEY'
  | 'DECRYPT_FAILED'
  | 'INVALID_ARMOR'
  | 'TIMEOUT'
  | 'UNSUPPORTED'
  | 'UNKNOWN';

/** Error payload returned by the native host. */
export interface BridgeErrorShape {
  code: BridgeErrorCode;
  message: string;
}

export interface BridgeSuccess<T> {
  id: string;
  ok: true;
  result: T;
}

export interface BridgeFailure {
  id: string;
  ok: false;
  error: BridgeErrorShape;
}

export type BridgeResponse<T = unknown> = BridgeSuccess<T> | BridgeFailure;

export type SignatureStatus = 'verified' | 'unverified' | 'invalid';
export type KeySource = 'WKD' | 'keys.openpgp.org' | 'Autocrypt';

export interface GenerateKeyParams {
  email: string;
  name?: string;
  passphrase?: string;
}

export interface KeyPairResult {
  fingerprint: string;
  publicKey: string;
  privateKey?: string;
  safetyNumber: string;
}

export interface EncryptParams {
  plaintext: string;
  recipients: string[];
  sign?: boolean;
  /** Reserved for the upcoming passphrase UI; never persisted by the extension. */
  passphrase?: string;
}

export interface EncryptResult {
  armored: string;
  fingerprint: string;
}

export interface DecryptParams {
  armored: string;
  verify?: boolean;
}

export interface DecryptResult {
  plaintext: string;
  signature: SignatureStatus;
  signerFingerprint?: string;
}

export interface SignParams {
  data: string;
  fingerprint?: string;
}

export interface SignResult {
  armored: string;
}

export interface VerifyParams {
  data: string;
  signature: string;
}

export interface VerifyResult {
  valid: boolean;
  signerFingerprint?: string;
}

export interface GetKeyParams {
  fingerprint?: string;
  email?: string;
}

export interface KeyInfo {
  fingerprint: string;
  email?: string;
  createdAt?: string;
  safetyNumber: string;
}

export interface DiscoverKeyParams {
  email: string;
}

export interface DiscoveredKey {
  email: string;
  fingerprint: string;
  safetyNumber: string;
  source: KeySource;
  confidence: 'high' | 'medium' | 'low';
}

/** The contract implemented by both the real native client and MockBridge. */
export interface Bridge {
  generateKey(params: GenerateKeyParams): Promise<KeyPairResult>;
  encrypt(params: EncryptParams): Promise<EncryptResult>;
  decrypt(params: DecryptParams): Promise<DecryptResult>;
  sign(params: SignParams): Promise<SignResult>;
  verify(params: VerifyParams): Promise<VerifyResult>;
  getKey(params: GetKeyParams): Promise<KeyInfo>;
  discoverKey(params: DiscoverKeyParams): Promise<DiscoveredKey>;
  /** Resolves when the native host is reachable. Never sends user data. */
  ping(): Promise<void>;
  close(): void;
}

/** How a message is composed before it is handed back to the webmail app. */
export type SendMode = 'send-both' | 'pgp-only';

export interface Settings {
  /** `send-both` keeps the typed message and appends PGP. `pgp-only` replaces it. */
  sendMode: SendMode;
  /** Master switch. When false, the extension leaves the draft untouched. */
  enabled: boolean;
  /** The user acknowledged the provider autosave warning. */
  autosaveAcknowledged: boolean;
}

/** Messages sent between extension contexts. No draft text is ever persisted. */
export type RuntimeMessage =
  | { type: 'PING' }
  | { type: 'DISCOVER_KEY'; email: string }
  | { type: 'ENCRYPT'; plaintext: string; recipients: string[]; passphrase?: string }
  | { type: 'DECRYPT_ARMORED'; armored: string }
  | { type: 'GET_SETTINGS' }
  | { type: 'SET_SETTINGS'; settings: Settings };
