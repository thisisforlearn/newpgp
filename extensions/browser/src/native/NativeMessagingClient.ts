// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Vaibhav - https://github.com/thisisforlearn
/**
 * Native host: `com.newpgp.bridge`.
 *
 * This is the NewPGP Rust CLI/daemon installed alongside the desktop app. It
 * owns private keys and does all heavy OpenPGP work; the MV3 service worker
 * only shuttles typed JSON requests and responses. Chromium/Firefox apply the
 * 4-byte little-endian framing described in `platform/framing.ts` when we use
 * `runtime.connectNative`, and we additionally support a raw-byte transport
 * (used by tests and any future socket-based bridge) through the same codec.
 */

import { FrameDecoder, encodeMessage } from '../platform/framing';
import { makeId } from '../platform/id';
import type { BrowserApi } from '../platform/browser';
import { bridgeError, isBridgeErrorShape } from './errors';
import type {
  Bridge,
  BridgeErrorCode,
  BridgeMethod,
  BridgeResponse,
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
  VerifyParams,
  VerifyResult,
} from '../types';

export const NATIVE_HOST_NAME = 'com.newpgp.bridge';

export interface NativeTransport {
  post(message: unknown): void;
  onMessage(listener: (message: unknown) => void): () => void;
  onDisconnect(listener: () => void): () => void;
  /** Optional: framed transports report a decode failure so pending work can fail fast. */
  onDecodeError?(listener: (error: unknown) => void): () => void;
  close(): void;
}

interface PendingRequest {
  resolve: (value: unknown) => void;
  reject: (error: unknown) => void;
  timer: ReturnType<typeof setTimeout>;
}

export interface NativeClientOptions {
  host?: string;
  timeoutMs?: number;
  transportFactory?: (api: BrowserApi, host: string) => NativeTransport;
}

/** Default transport backed by `runtime.connectNative`. */
export function createPortTransport(api: BrowserApi, host: string): NativeTransport {
  const port = api.runtime.connectNative(host);
  return {
    post(message) {
      port.postMessage(message);
    },
    onMessage(listener) {
      const wrapped = (message: unknown): void => listener(message);
      port.onMessage.addListener(wrapped);
      return () => port.onMessage.removeListener(wrapped);
    },
    onDisconnect(listener) {
      const wrapped = (): void => listener();
      port.onDisconnect.addListener(wrapped);
      return () => port.onDisconnect.removeListener(wrapped);
    },
    close() {
      port.disconnect();
    },
  };
}

export interface RawByteTransport {
  send(bytes: Uint8Array): void;
  onReceive(listener: (bytes: Uint8Array) => void): () => void;
  onClose(listener: () => void): () => void;
  close(): void;
}

/** Transport that performs the native messaging framing itself. */
export function createFramedTransport(raw: RawByteTransport): NativeTransport {
  const decoder = new FrameDecoder();
  const decodeErrorListeners = new Set<(error: unknown) => void>();
  return {
    post(message) {
      raw.send(encodeMessage(message));
    },
    onMessage(listener) {
      return raw.onReceive((bytes) => {
        let messages: unknown[];
        try {
          messages = decoder.push(bytes);
        } catch (error) {
          decoder.reset();
          for (const onError of decodeErrorListeners) onError(error);
          return;
        }
        for (const message of messages) listener(message);
      });
    },
    onDisconnect(listener) {
      return raw.onClose(listener);
    },
    onDecodeError(listener) {
      decodeErrorListeners.add(listener);
      return () => decodeErrorListeners.delete(listener);
    },
    close() {
      raw.close();
    },
  };
}

function isBridgeResponse(value: unknown): value is BridgeResponse {
  if (typeof value !== 'object' || value === null) return false;
  const candidate = value as Partial<BridgeResponse>;
  return typeof candidate.id === 'string' && typeof candidate.ok === 'boolean';
}

export class NativeMessagingClient implements Bridge {
  private readonly host: string;
  private readonly timeoutMs: number;
  private transport: NativeTransport | null = null;
  private readonly pending = new Map<string, PendingRequest>();
  private readonly decoder = new FrameDecoder();
  private unsubscribers: Array<() => void> = [];

  constructor(
    private readonly api: BrowserApi,
    private readonly options: NativeClientOptions = {},
  ) {
    this.host = options.host ?? NATIVE_HOST_NAME;
    this.timeoutMs = options.timeoutMs ?? 30_000;
  }

  private ensureTransport(): NativeTransport {
    if (this.transport) return this.transport;
    let transport: NativeTransport;
    try {
      const factory = this.options.transportFactory ?? createPortTransport;
      transport = factory(this.api, this.host);
    } catch {
      throw bridgeError('NATIVE_HOST_NOT_FOUND', `Native host ${this.host} is not installed.`);
    }
    this.transport = transport;
    this.unsubscribers = [
      transport.onMessage((message) => this.handleIncoming(message)),
      transport.onDisconnect(() => this.handleDisconnect()),
    ];
    if (transport.onDecodeError) {
      this.unsubscribers.push(
        transport.onDecodeError(() => this.handleProtocolError('The native host sent a malformed message.')),
      );
    }
    return transport;
  }

  /** Reject every outstanding request without leaving timers running. */
  private failPending(code: BridgeErrorCode, message: string): void {
    for (const [id, pending] of this.pending) {
      clearTimeout(pending.timer);
      this.pending.delete(id);
      pending.reject(bridgeError(code, message));
    }
  }

  private handleProtocolError(message: string): void {
    this.decoder.reset();
    this.failPending('BAD_REQUEST', message);
  }

  private handleIncoming(message: unknown): void {
    if (
      !(message instanceof Uint8Array) &&
      !(message instanceof ArrayBuffer) &&
      !ArrayBuffer.isView(message)
    ) {
      this.handleResponse(message);
      return;
    }
    let framed: unknown[];
    try {
      framed = this.decoder.push(message);
    } catch {
      this.handleProtocolError('The native host sent a malformed frame.');
      return;
    }
    for (const candidate of framed) this.handleResponse(candidate);
  }

  private handleResponse(message: unknown): void {
    if (!isBridgeResponse(message)) return;
    const pending = this.pending.get(message.id);
    if (!pending) return;
    this.pending.delete(message.id);
    clearTimeout(pending.timer);

    if (message.ok) {
      pending.resolve(message.result);
      return;
    }
    const error = isBridgeErrorShape(message.error)
      ? bridgeError(message.error.code, message.error.message)
      : bridgeError('BAD_REQUEST', 'Malformed error from native host.');
    pending.reject(error);
  }

  private handleDisconnect(): void {
    this.failPending('BRIDGE_DISCONNECTED', 'Native host disconnected.');
    this.releaseTransport();
  }

  private releaseTransport(): void {
    for (const unsubscribe of this.unsubscribers) unsubscribe();
    this.unsubscribers = [];
    this.transport = null;
  }

  private request<T>(method: BridgeMethod, params: unknown): Promise<T> {
    const transport = this.ensureTransport();
    const id = makeId();
    return new Promise<T>((resolve, reject) => {
      const timer = setTimeout(() => {
        this.pending.delete(id);
        reject(bridgeError('TIMEOUT', 'Native host did not answer in time.'));
      }, this.timeoutMs);
      this.pending.set(id, { resolve: resolve as (value: unknown) => void, reject, timer });
      try {
        transport.post({ id, method, params });
      } catch (error) {
        clearTimeout(timer);
        this.pending.delete(id);
        reject(
          bridgeError('BRIDGE_DISCONNECTED', error instanceof Error ? error.message : 'Could not reach native host.'),
        );
      }
    });
  }

  generateKey(params: GenerateKeyParams): Promise<KeyPairResult> {
    return this.request<KeyPairResult>('generateKey', params);
  }

  encrypt(params: EncryptParams): Promise<EncryptResult> {
    return this.request<EncryptResult>('encrypt', params);
  }

  decrypt(params: DecryptParams): Promise<DecryptResult> {
    return this.request<DecryptResult>('decrypt', params);
  }

  sign(params: SignParams): Promise<SignResult> {
    return this.request<SignResult>('sign', params);
  }

  verify(params: VerifyParams): Promise<VerifyResult> {
    return this.request<VerifyResult>('verify', params);
  }

  getKey(params: GetKeyParams): Promise<KeyInfo> {
    return this.request<KeyInfo>('getKey', params);
  }

  discoverKey(params: DiscoverKeyParams): Promise<DiscoveredKey> {
    return this.request<DiscoveredKey>('discoverKey', params);
  }

  async ping(): Promise<void> {
    this.ensureTransport();
  }

  close(): void {
    this.failPending('BRIDGE_DISCONNECTED', 'Native client closed.');
    this.transport?.close();
    this.releaseTransport();
  }
}

/** Helper for native-host authors and tests: build a typed success reply. */
export function bridgeSuccess<T>(id: string, result: T): BridgeResponse<T> {
  return { id, ok: true, result };
}

/** Helper for native-host authors and tests: build a typed failure reply. */
export function bridgeFailure(id: string, code: BridgeErrorCode, message: string): BridgeResponse<never> {
  return { id, ok: false, error: bridgeError(code, message) };
}
