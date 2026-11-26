// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Vaibhav - https://github.com/thisisforlearn
import { afterEach, describe, expect, it, vi } from 'vitest';
import {
  FrameDecoder,
  encodeMessage,
  readUint32LE,
} from '../src/platform/framing';
import {
  NATIVE_HOST_NAME,
  NativeMessagingClient,
  createFramedTransport,
  createPortTransport,
  type RawByteTransport,
} from '../src/native/NativeMessagingClient';
import { toFriendlyError } from '../src/native/errors';
import { createMockBrowserApi } from './mocks/browser';
import type { BridgeRequest } from '../src/types';

function framedRequest(bytes: Uint8Array): BridgeRequest {
  const message = new FrameDecoder().push(bytes)[0] as BridgeRequest;
  return message;
}

interface Loopback {
  raw: RawByteTransport;
  sent: Uint8Array[];
  requests: BridgeRequest[];
  close(): void;
  pushResponse(message: unknown, splitAt?: number): void;
  pushBytes(bytes: Uint8Array): void;
}

function createLoopback(responder: (request: BridgeRequest) => unknown): Loopback {
  let receive: (bytes: Uint8Array) => void = () => undefined;
  let closed: () => void = () => undefined;
  const sent: Uint8Array[] = [];
  const requests: BridgeRequest[] = [];

  const raw: RawByteTransport = {
    send(bytes) {
      sent.push(bytes);
      const request = framedRequest(bytes);
      requests.push(request);
      const response = responder(request);
      if (response !== undefined) receive(encodeMessage(response));
    },
    onReceive(listener) {
      receive = listener;
      return () => {
        receive = () => undefined;
      };
    },
    onClose(listener) {
      closed = listener;
      return () => {
        closed = () => undefined;
      };
    },
    close() {
      closed();
    },
  };

  return {
    raw,
    sent,
    requests,
    close() {
      closed();
    },
    pushResponse(message, splitAt) {
      const framed = encodeMessage(message);
      if (splitAt === undefined) {
        receive(framed);
        return;
      }
      receive(framed.subarray(0, splitAt));
      receive(framed.subarray(splitAt));
    },
    pushBytes(bytes) {
      receive(bytes);
    },
  };
}

afterEach(() => {
  vi.useRealTimers();
});

describe('NativeMessagingClient', () => {
  it('uses the com.newpgp.bridge native host and sends framed JSON requests', async () => {
    const api = createMockBrowserApi();
    const client = new NativeMessagingClient(api, {
      transportFactory: () => createPortTransport(api, NATIVE_HOST_NAME),
    });
    const port = api.ports[0];
    await client.ping();
    port?.emitMessage({ id: 'unknown', ok: true, result: null });
    client.close();
    expect(api.connectedHosts).toContain('com.newpgp.bridge');
  });

  it('correlates concurrent responses by id over a framed transport', async () => {
    const loopback = createLoopback((request) => ({
      id: request.id,
      ok: true,
      result: { method: request.method, params: request.params },
    }));
    const api = createMockBrowserApi();
    const client = new NativeMessagingClient(api, {
      transportFactory: () => createFramedTransport(loopback.raw),
    });

    const [encrypt, discover] = await Promise.all([
      client.encrypt({ plaintext: 'hello', recipients: ['a@example.com'] }),
      client.discoverKey({ email: 'a@example.com' }),
    ]);

    expect(encrypt).toEqual({ method: 'encrypt', params: { plaintext: 'hello', recipients: ['a@example.com'] } });
    expect(discover).toEqual({ method: 'discoverKey', params: { email: 'a@example.com' } });
    expect(loopback.requests.map((request) => request.method)).toEqual(['encrypt', 'discoverKey']);
    expect(readUint32LE(loopback.sent[0] ?? new Uint8Array(4))).toBe((loopback.sent[0]?.byteLength ?? 4) - 4);
    client.close();
  });

  it('parses a framed response split across two chunks', async () => {
    const loopback = createLoopback(() => undefined);
    const api = createMockBrowserApi();
    const client = new NativeMessagingClient(api, {
      transportFactory: () => createFramedTransport(loopback.raw),
    });
    const promise = client.decrypt({ armored: 'block' });
    const request = loopback.requests[0];
    expect(request).toBeDefined();
    const framed = encodeMessage({ id: request?.id, ok: true, result: { plaintext: 'hi', signature: 'verified' } });
    loopback.pushResponse({ id: request?.id, ok: true, result: { plaintext: 'hi', signature: 'verified' } }, 3);
    expect(framed.length).toBeGreaterThan(3);
    await expect(promise).resolves.toEqual({ plaintext: 'hi', signature: 'verified' });
    client.close();
  });

  it('rejects bridge errors with their typed code', async () => {
    const loopback = createLoopback((request) => ({
      id: request.id,
      ok: false,
      error: { code: 'NO_KEY', message: 'no key' },
    }));
    const api = createMockBrowserApi();
    const client = new NativeMessagingClient(api, {
      transportFactory: () => createFramedTransport(loopback.raw),
    });
    await expect(client.discoverKey({ email: 'x@example.com' })).rejects.toMatchObject({ code: 'NO_KEY' });
    client.close();
  });

  it('rejects pending requests when the host disconnects', async () => {
    const loopback = createLoopback(() => undefined);
    const api = createMockBrowserApi();
    const client = new NativeMessagingClient(api, {
      transportFactory: () => createFramedTransport(loopback.raw),
    });
    const promise = client.encrypt({ plaintext: 'hi', recipients: [] });
    loopback.close();
    await expect(promise).rejects.toMatchObject({ code: 'BRIDGE_DISCONNECTED' });
  });

  it('times out and maps to a friendly message', async () => {
    vi.useFakeTimers();
    const loopback = createLoopback(() => undefined);
    const api = createMockBrowserApi();
    const client = new NativeMessagingClient(api, {
      timeoutMs: 100,
      transportFactory: () => createFramedTransport(loopback.raw),
    });
    const promise = client.encrypt({ plaintext: 'hi', recipients: [] });
    const assertion = expect(promise).rejects.toMatchObject({ code: 'TIMEOUT' });
    await vi.advanceTimersByTimeAsync(101);
    await assertion;
    await expect(Promise.resolve(toFriendlyError({ code: 'TIMEOUT', message: 'x' }))).resolves.toMatchObject({
      title: expect.stringMatching(/too long/),
    });
    client.close();
  });

  it('fails pending requests fast on a malformed frame and then recovers', async () => {
    const loopback = createLoopback(() => undefined);
    const api = createMockBrowserApi();
    const client = new NativeMessagingClient(api, {
      transportFactory: () => createFramedTransport(loopback.raw),
    });

    const broken = client.encrypt({ plaintext: 'hi', recipients: [] });
    loopback.pushBytes(new Uint8Array([0, 0, 0x11, 0x00])); // oversize length prefix
    await expect(broken).rejects.toMatchObject({ code: 'BAD_REQUEST' });

    const recovered = client.decrypt({ armored: 'block' });
    const request = loopback.requests[1];
    loopback.pushResponse({ id: request?.id, ok: true, result: { plaintext: 'ok', signature: 'verified' } });
    await expect(recovered).resolves.toEqual({ plaintext: 'ok', signature: 'verified' });
    client.close();
  });

  it('rejects pending raw-byte requests instead of waiting for the timeout', async () => {
    const api = createMockBrowserApi();
    const client = new NativeMessagingClient(api);
    const promise = client.encrypt({ plaintext: 'hi', recipients: [] });
    api.ports[0]?.emitMessage(new Uint8Array([0, 0, 0x11, 0x00]));
    await expect(promise).rejects.toMatchObject({ code: 'BAD_REQUEST' });
    client.close();
  });

  it('maps a missing native host to a friendly installation error', async () => {
    const api = createMockBrowserApi({ nativeHostAvailable: false });
    const client = new NativeMessagingClient(api);
    await expect(client.ping()).rejects.toMatchObject({ code: 'NATIVE_HOST_NOT_FOUND' });
    expect(toFriendlyError({ code: 'NATIVE_HOST_NOT_FOUND', message: 'x' }).hint).toMatch(/install/i);
  });
});
