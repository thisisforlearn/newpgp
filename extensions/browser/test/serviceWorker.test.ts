// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Vaibhav - https://github.com/thisisforlearn
import { describe, expect, it, vi } from 'vitest';
import { createMockBrowserApi } from './mocks/browser';

async function loadWorker(api: ReturnType<typeof createMockBrowserApi>): Promise<void> {
  (globalThis as unknown as { chrome: unknown }).chrome = api;
  (globalThis as unknown as { browser: unknown }).browser = api;
  vi.resetModules();
  await import('../src/background/service-worker');
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
