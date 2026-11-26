// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Vaibhav - https://github.com/thisisforlearn
import { describe, expect, it, vi } from 'vitest';
import { callBackground } from '../src/messages';
import { createMockBrowserApi, MockEvent } from './mocks/browser';

async function loadWorker(api: ReturnType<typeof createMockBrowserApi>): Promise<void> {
  (globalThis as unknown as { chrome: unknown }).chrome = api;
  (globalThis as unknown as { browser: unknown }).browser = api;
  vi.resetModules();
  await import('../src/background/service-worker');
}

/** A mock API whose native port answers every bridge request successfully. */
function createApiWithBridgeReply(): ReturnType<typeof createMockBrowserApi> {
  return createMockBrowserApi({
    onConnect: (_host, port) => {
      const post = port.postMessage.bind(port);
      port.postMessage = (message: unknown) => {
        post(message);
        const id = (message as { id?: string }).id;
        if (typeof id === 'string') {
          port.emitMessage({ id, ok: true, result: { armored: 'ARMOR', fingerprint: 'FINGERPRINT' } });
        }
      };
    },
  });
}

describe('service worker sender validation', () => {
  it('answers requests from its own extension pages', async () => {
    const api = createMockBrowserApi();
    await loadWorker(api);
    const response = await api.emitRuntimeMessage({ type: 'GET_SETTINGS' }, { id: api.runtime.id });
    expect(response).toMatchObject({ ok: true });
  });

  it('refuses to drive the native host for a foreign extension', async () => {
    const api = createMockBrowserApi();
    await loadWorker(api);
    const response = await api.emitRuntimeMessage(
      { type: 'ENCRYPT', plaintext: 'Secret meeting', recipients: ['friend@example.com'] },
      { id: 'some-other-extension' },
    );
    expect(response).toMatchObject({ ok: false });
    expect(JSON.stringify(response)).not.toContain('Secret meeting');
    expect(api.connectedHosts).toHaveLength(0);
  });
});

describe('service worker encryption contract', () => {
  it('sends the default encrypt request unsigned, without a passphrase', async () => {
    const api = createApiWithBridgeReply();
    await loadWorker(api);
    const response = await api.emitRuntimeMessage(
      { type: 'ENCRYPT', plaintext: 'Secret meeting', recipients: ['friend@example.com'] },
      { id: api.runtime.id },
    );
    expect(response).toMatchObject({ ok: true });

    const request = api.ports[0]?.sent[0] as { method: string; params: Record<string, unknown> };
    expect(request.method).toBe('encrypt');
    expect(request.params.sign).toBe(false);
    expect(request.params.plaintext).toBe('Secret meeting');
    expect('passphrase' in request.params).toBe(false);
  });

  it('forwards a supplied passphrase to the bridge but keeps signing off', async () => {
    const api = createApiWithBridgeReply();
    await loadWorker(api);
    await api.emitRuntimeMessage(
      {
        type: 'ENCRYPT',
        plaintext: 'Secret meeting',
        recipients: ['friend@example.com'],
        passphrase: 'open sesame',
      },
      { id: api.runtime.id },
    );

    const request = api.ports[0]?.sent[0] as { method: string; params: Record<string, unknown> };
    expect(request.params.sign).toBe(false);
    expect(request.params.passphrase).toBe('open sesame');
  });
});

describe('Chrome-compatible message replies', () => {
  it('delivers a success reply to runtime.sendMessage callers', async () => {
    const api = createMockBrowserApi();
    await loadWorker(api);
    const response = await callBackground(api, { type: 'GET_SETTINGS' });
    expect(response.ok).toBe(true);
    expect(response.result).toMatchObject({ enabled: true, sendMode: 'send-both' });
  });

  it('delivers a friendly error reply to runtime.sendMessage callers', async () => {
    const api = createMockBrowserApi();
    await loadWorker(api);
    const response = await callBackground(api, { type: 'DISCOVER_KEY', email: 'not-an-email' });
    expect(response.ok).toBe(false);
    expect(response.error?.title).toBe('Please check that email address');
    expect(JSON.stringify(response)).not.toContain('not-an-email');
  });

  it('waits for an asynchronous sendResponse after the listener returns true', async () => {
    const event = new MockEvent<unknown>();
    let keepChannelOpen: boolean | void = undefined;
    event.addListener((_value, _sender, sendResponse) => {
      keepChannelOpen = true;
      void Promise.resolve().then(() => sendResponse?.({ ok: true, result: 'late reply' }));
      return true;
    });

    const reply = event.emitAsync({ type: 'PING' });
    expect(keepChannelOpen).toBe(true);
    await expect(reply).resolves.toMatchObject({ ok: true, result: 'late reply' });
  });
});
