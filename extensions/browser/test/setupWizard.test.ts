// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Vaibhav - https://github.com/thisisforlearn
/**
 * First-run wizard: pure step transitions, DOM copy, and the real popup flow
 * against a mocked extension API and native bridge.
 */
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { describe, expect, it, vi } from 'vitest';
import { DEFAULT_SETTINGS, SETUP_KEY } from '../src/composition/settings';
import { ok } from '../src/messages';
import { createMockBrowserApi, type MockBrowserApi } from './mocks/browser';

const popupHtml = readFileSync(join(process.cwd(), 'src', 'ui', 'popup.html'), 'utf8');
const popupStyles = readFileSync(join(process.cwd(), 'src', 'ui', 'styles.css'), 'utf8');
const popupSource = readFileSync(join(process.cwd(), 'src', 'ui', 'popup.ts'), 'utf8');
const popupBody = popupHtml.match(/<body[^>]*>([\s\S]*?)<\/body>/)?.[1] ?? '';

type PopupModule = typeof import('../src/ui/popup');
type KeyReply =
  | { ok: true; result: { fingerprint: string } }
  | { ok: false; error: { code: string; message: string } };

interface HarnessControl {
  pingOk: boolean;
  keyReply: KeyReply;
}

interface Harness {
  api: MockBrowserApi;
  mod: PopupModule;
  control: HarnessControl;
  generateRequests: unknown[];
}

async function loadPopup(options: Partial<HarnessControl> = {}): Promise<Harness> {
  document.body.innerHTML = popupBody;
  const control: HarnessControl = {
    pingOk: options.pingOk ?? true,
    keyReply: options.keyReply ?? { ok: true, result: { fingerprint: 'ABCDEF1234567890' } },
  };
  const generateRequests: unknown[] = [];
  const api = createMockBrowserApi({
    onConnect: (_host, port) => {
      const post = port.postMessage.bind(port);
      port.postMessage = (message: unknown) => {
        post(message);
        const id = (message as { id?: string }).id;
        if (typeof id !== 'string' || (message as { method?: string }).method !== 'generateKey') return;
        generateRequests.push(message);
        port.emitMessage({ id, ...control.keyReply });
      };
    },
  });

  // Stand in for the shipped worker: PING works, but it predates GENERATE_KEY.
  api.runtime.onMessage.addListener((raw, _sender, sendResponse) => {
    const message = raw as { type?: string };
    if (message.type === 'GET_SETTINGS') {
      sendResponse?.(ok(DEFAULT_SETTINGS));
      return false;
    }
    if (message.type === 'PING') {
      sendResponse?.(
        control.pingOk
          ? ok({ connected: true })
          : {
              ok: false,
              error: {
                title: "The NewPGP helper isn't installed",
                message: 'We could not find the NewPGP helper on this computer.',
                hint: 'Install the NewPGP desktop app (or CLI daemon), then reload the extension.',
              },
            },
      );
      return false;
    }
    if (message.type === 'GENERATE_KEY') {
      sendResponse?.(
        options.keyReply && 'result' in options.keyReply && options.keyReply.result.fingerprint === 'ROUTED'
          ? ok({ fingerprint: 'ROUTED' })
          : {
              ok: false,
              error: { title: 'Unknown request', message: 'NewPGP did not recognise this request.' },
            },
      );
      return false;
    }
    return false;
  });

  (globalThis as unknown as { chrome: unknown }).chrome = api;
  (globalThis as unknown as { browser: unknown }).browser = api;
  vi.resetModules();
  const mod = await import('../src/ui/popup');
  await vi.waitFor(() => {
    expect((document.querySelector('#mode-description') as HTMLElement).textContent).toBeTruthy();
  });
  return { api, mod, control, generateRequests };
}

function text(selector: string): string {
  return document.querySelector(selector)?.textContent ?? '';
}

function shown(selector: string): boolean {
  return !(document.querySelector(selector) as HTMLElement).hidden;
}

function click(selector: string): void {
  (document.querySelector(selector) as HTMLElement).click();
}

function fillKeyDetails(): void {
  (document.querySelector('#setup-name') as HTMLInputElement).value = 'Alex Example';
  (document.querySelector('#setup-email') as HTMLInputElement).value = 'alex@example.com';
}

describe('setup wizard state machine', () => {
  it('starts on step 1 with no assumed connection or key', async () => {
    const { mod } = await loadPopup();
    expect(mod.INITIAL_SETUP_STATE).toEqual({
      step: 1,
      connected: false,
      fingerprint: null,
      error: null,
    });
  });

  it('advances with a successful ping and not with a failed one', async () => {
    const { mod } = await loadPopup();
    const failed = mod.reduceSetup(mod.INITIAL_SETUP_STATE, {
      type: 'PING_FAILED',
      notice: mod.pingFailureNotice(),
    });
    expect(failed.step).toBe(1);
    expect(failed.error?.title).toBe('Not yet — try these steps');
    expect(failed.connected).toBe(false);

    const passed = mod.reduceSetup(failed, { type: 'PING_SUCCEEDED' });
    expect(passed.step).toBe(2);
    expect(passed.connected).toBe(true);
    expect(passed.error).toBeNull();
  });

  it('never repeats the false daemon claim from older backend hints', async () => {
    const { mod } = await loadPopup();
    const ping = mod.pingFailureNotice({ title: 'x', message: 'Helper missing', hint: 'CLI daemon' });
    const key = mod.keyFailureNotice({ title: 'x', message: 'Helper missing', hint: 'CLI daemon' });
    for (const notice of [ping, key]) {
      expect(`${notice.title} ${notice.message} ${notice.hint ?? ''}`).not.toMatch(/daemon|desktop app/i);
    }
  });

  it('requires a real fingerprint to leave step 2', async () => {
    const { mod } = await loadPopup();
    const ready = mod.reduceSetup(mod.INITIAL_SETUP_STATE, { type: 'PING_SUCCEEDED' });
    expect(mod.reduceSetup(ready, { type: 'KEY_CREATED', fingerprint: '   ' }).step).toBe(2);

    const failed = mod.reduceSetup(ready, {
      type: 'KEY_FAILED',
      notice: mod.keyFailureNotice(),
    });
    expect(failed.step).toBe(2);
    expect(failed.error?.title).toBe("We couldn't make your key yet");

    const created = mod.reduceSetup(failed, { type: 'KEY_CREATED', fingerprint: ' ABCD ' });
    expect(created.step).toBe(3);
    expect(created.fingerprint).toBe('ABCD');
    expect(created.error).toBeNull();
  });

  it('ignores out-of-order events and only finishes from step 3', async () => {
    const { mod } = await loadPopup();
    const step2 = mod.reduceSetup(mod.INITIAL_SETUP_STATE, { type: 'PING_SUCCEEDED' });
    expect(mod.reduceSetup(mod.INITIAL_SETUP_STATE, { type: 'KEY_CREATED', fingerprint: 'ABCD' })).toEqual(
      mod.INITIAL_SETUP_STATE,
    );
    expect(mod.reduceSetup(step2, { type: 'FINISHED' })).toEqual(step2);

    const step3 = mod.reduceSetup(step2, { type: 'KEY_CREATED', fingerprint: 'ABCD' });
    expect(mod.reduceSetup(step3, { type: 'FINISHED' }).step).toBe('done');
    expect(mod.reduceSetup(step3, { type: 'RESTORE_COMPLETED' }).step).toBe('done');
    expect(mod.reduceSetup(step3, { type: 'RESET' })).toEqual(mod.INITIAL_SETUP_STATE);
  });
});

describe('setup wizard popup flow', () => {
  it('advances only after the helper answers and the key is made', async () => {
    const { api, control, generateRequests } = await loadPopup({ pingOk: false });

    expect(shown('#setup-wizard')).toBe(true);
    expect(shown('#setup-step-1')).toBe(true);
    expect(text('#setup-step-label')).toBe('Step 1 of 3');

    click('#setup-ping-button');
    await vi.waitFor(() => expect(text('#setup-feedback')).toContain('Not yet — try these steps'));
    expect(text('#setup-feedback')).not.toMatch(/daemon|desktop app/i);
    expect(text('#setup-feedback')).toContain('use your exact extension ID');
    expect(shown('#setup-step-1')).toBe(true);
    expect(shown('#setup-step-2')).toBe(false);

    control.pingOk = true;
    click('#setup-ping-button');
    await vi.waitFor(() => expect(shown('#setup-step-2')).toBe(true));
    expect(text('#setup-feedback')).toContain('Connected');
    expect(shown('#setup-step-3')).toBe(false);

    fillKeyDetails();
    click('#setup-create-key');
    await vi.waitFor(() => expect(shown('#setup-step-3')).toBe(true));
    expect(text('#setup-feedback')).toContain('Your key is ready');
    expect(text('#setup-feedback')).toContain('ABCD EF12 3456 7890');
    expect(generateRequests).toHaveLength(1);
    expect((generateRequests[0] as { method: string }).method).toBe('generateKey');
    expect(JSON.stringify(api.store)).not.toContain('Alex Example');

    const open = vi.spyOn(window, 'open').mockImplementation(() => null);
    click('#setup-done-gmail');
    await vi.waitFor(() => expect(shown('#setup-wizard')).toBe(false));
    expect(open).toHaveBeenCalledWith(expect.stringContaining('https://mail.google.com'), '_blank', 'noopener');
    expect(api.store[SETUP_KEY]).toEqual({ completed: true });
  });

  it('shows a friendly message and stays on step 2 when key creation fails', async () => {
    const { control } = await loadPopup({
      keyReply: { ok: false, error: { code: 'BAD_REQUEST', message: 'ignored raw code' } },
    });

    click('#setup-ping-button');
    await vi.waitFor(() => expect(shown('#setup-step-2')).toBe(true));
    fillKeyDetails();
    click('#setup-create-key');
    await vi.waitFor(() => expect(text('#setup-feedback')).toContain("We couldn't make your key yet"));
    expect(text('#setup-feedback')).toContain('helper did not understand the request');
    expect(shown('#setup-step-2')).toBe(true);
    expect(shown('#setup-step-3')).toBe(false);

    control.keyReply = { ok: true, result: { fingerprint: 'FEDCBA9876543210' } };
    click('#setup-create-key');
    await vi.waitFor(() => expect(shown('#setup-step-3')).toBe(true));
    expect(text('#setup-feedback')).toContain('FEDC BA98 7654 3210');
  });

  it('uses the background router directly when that route exists', async () => {
    const { generateRequests } = await loadPopup({
      keyReply: { ok: true, result: { fingerprint: 'ROUTED' } },
    });

    click('#setup-ping-button');
    await vi.waitFor(() => expect(shown('#setup-step-2')).toBe(true));
    fillKeyDetails();
    click('#setup-create-key');
    await vi.waitFor(() => expect(shown('#setup-step-3')).toBe(true));
    expect(text('#setup-feedback')).toContain('Your key is ready');
    expect(generateRequests).toHaveLength(0);
  });

  it('does not show the wizard again after setup was completed', async () => {
    const { api } = await loadPopup();
    await api.storage.local.set({ [SETUP_KEY]: { completed: true } });
    vi.resetModules();
    document.body.innerHTML = popupBody;
    (globalThis as unknown as { chrome: unknown }).chrome = api;
    (globalThis as unknown as { browser: unknown }).browser = api;
    await import('../src/ui/popup');
    await vi.waitFor(() => expect(shown('#setup-wizard')).toBe(false));
  });
});

describe('setup wizard copy and sizing', () => {
  it('uses plain language, exact installer links, and no false daemon claim', () => {
    expect(popupHtml).toContain('Set up NewPGP in about 5 minutes');
    expect(popupHtml).toContain('1. Install the helper');
    expect(popupHtml).toContain('2. Make your key');
    expect(popupHtml).toContain('3. Write normally');
    expect(popupHtml).toContain('native-host/install.ps1');
    expect(popupHtml).toContain('native-host/install.sh');
    expect(popupHtml).toContain('I installed it');
    expect(popupHtml).toContain('Create my key');
    expect(popupHtml).toContain('Done — open Gmail');
    expect(popupHtml).toContain('https://mail.google.com');
    expect(popupHtml).toContain('https://outlook.live.com');
    expect(popupHtml.toLowerCase()).not.toMatch(/daemon|background service|keep it running/);
  });

  it('keeps wizard text at 16px or more and buttons at 44px or more', () => {
    expect(popupStyles).toMatch(/\.wizard\s*\{[^}]*font-size:\s*(1[6-9]|[2-9]\d|\d{3})px/s);
    expect(popupStyles).toMatch(/\.big-button\s*\{[^}]*min-height:\s*(4[4-9]|[5-9]\d|\d{3})px/s);
  });

  it('renders every dynamic copy with textContent, never innerHTML', () => {
    expect(popupSource).not.toContain('innerHTML');
  });
});
