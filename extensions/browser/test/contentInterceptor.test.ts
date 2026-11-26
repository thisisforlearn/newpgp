// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Vaibhav - https://github.com/thisisforlearn
/**
 * Compose-interception regressions: the clicked button determines the editor,
 * failed encryption blocks the send, and rich text is preserved.
 */
import { describe, expect, it } from 'vitest';
import { installComposeInterceptor, type ComposeSelectors } from '../src/content/common';
import { createMockBrowserApi } from './mocks/browser';
import type { Settings } from '../src/types';

const ARMOR = ['-----BEGIN PGP MESSAGE-----', '', 'QUJDRA==', '-----END PGP MESSAGE-----'].join('\n');
const selectors: ComposeSelectors = {
  editor: ['[data-editor]'],
  sendButton: ['button[name="send"]'],
  recipients: ['[aria-label="To"]'],
};

interface Harness {
  uninstall: () => void;
  encryptCalls: Array<{ plaintext: string; recipients: string[] }>;
  sends: string[];
  editors: HTMLElement[];
  buttons: HTMLButtonElement[];
}

function harness(
  options: { settings?: Partial<Settings>; armored?: string; settingsFails?: boolean; trusted?: boolean } = {},
  bodies: string[] = ['Secret draft'],
): Harness {
  const api = createMockBrowserApi();
  const encryptCalls: Harness['encryptCalls'] = [];
  api.runtime.sendMessage = async (message: any) => {
    if (message.type === 'GET_SETTINGS') {
      if (options.settingsFails) return { ok: false, error: { title: 'Storage', message: 'unavailable' } };
      return { ok: true, result: { ...DEFAULT, ...options.settings } };
    }
    if (message.type === 'ENCRYPT') {
      encryptCalls.push({ plaintext: message.plaintext, recipients: message.recipients });
      return {
        ok: true,
        result: { armored: options.armored ?? ARMOR, sendMode: options.settings?.sendMode ?? 'send-both' },
      };
    }
    return { ok: true, result: undefined };
  };

  const editors: HTMLElement[] = [];
  const buttons: HTMLButtonElement[] = [];
  const sends: string[] = [];
  bodies.forEach((body, index) => {
    const compose = document.createElement('div');
    compose.className = 'compose';

    const editor = document.createElement('div');
    editor.setAttribute('contenteditable', 'true');
    editor.setAttribute('role', 'textbox');
    editor.dataset.editor = String(index);
    editor.innerHTML = body;

    const field = document.createElement('input');
    field.setAttribute('aria-label', 'To');
    field.value = `friend${index}@example.com`;

    const button = document.createElement('button');
    button.name = 'send';
    button.textContent = 'Send';
    // Stand-in for the webmail provider's own send handler.
    button.addEventListener('click', () => sends.push(editor.textContent ?? ''));

    compose.append(editor, field, button);
    document.body.append(compose);
    editors.push(editor);
    buttons.push(button);
  });

  return {
    uninstall: installComposeInterceptor({
      api,
      selectors,
      isTrustedEvent: () => options.trusted !== false,
    }),
    encryptCalls,
    sends,
    editors,
    buttons,
  };
}

const DEFAULT: Settings = { sendMode: 'send-both', enabled: true, autosaveAcknowledged: false };

// jsdom hard-codes `isTrusted` on every event, so the harness injects the
// predicate instead. Production always uses the browser's real value.
function trustedClick(button: Element): void {
  button.dispatchEvent(new MouseEvent('click', { bubbles: true, cancelable: true }));
}

function flush(): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, 0));
}

describe('compose interceptor', () => {
  it('encrypts the editor of the CLICKED button, never a stale focused window', async () => {
    const h = harness({ settings: { sendMode: 'pgp-only' } }, ['Secret A', 'Secret B']);
    try {
      h.editors[0]?.focus();
      trustedClick(h.buttons[1]!);
      await flush();

      expect(h.encryptCalls).toHaveLength(1);
      expect(h.encryptCalls[0]?.plaintext).toBe('Secret B');
      expect(h.encryptCalls[0]?.recipients).toEqual(['friend1@example.com']);
      expect(h.editors[0]?.textContent).toBe('Secret A');
      expect(h.sends).toHaveLength(1);
      expect(h.sends[0]).toContain('-----BEGIN PGP MESSAGE-----');
      expect(h.sends[0]).not.toContain('Secret A');
      expect(h.sends[0]).not.toContain('Secret B');
    } finally {
      h.uninstall();
    }
  });

  for (const mode of ['send-both', 'pgp-only'] as const) {
    it(`${mode}: empty armor blocks the send and leaves the draft untouched`, async () => {
      const h = harness({ settings: { sendMode: mode }, armored: '   ' }, ['Secret draft']);
      try {
        trustedClick(h.buttons[0]!);
        await flush();

        expect(h.encryptCalls).toHaveLength(1);
        expect(h.sends).toHaveLength(0);
        expect(h.editors[0]?.textContent).toBe('Secret draft');
        const notice = document.querySelector('.newpgp-notice');
        expect(notice?.textContent).toMatch(/nothing was sent/i);
      } finally {
        h.uninstall();
      }
    });
  }

  it('preserves rich text in send-both and appends armor with DOM APIs', async () => {
    const h = harness({}, ['<b>Hello</b> Sam']);
    try {
      trustedClick(h.buttons[0]!);
      await flush();

      const editor = h.editors[0]!;
      expect(editor.innerHTML).toContain('<b>Hello</b>');
      const armor = editor.querySelector('pre.newpgp-armor');
      expect(armor?.textContent).toContain('-----BEGIN PGP MESSAGE-----');
      expect(armor?.innerHTML).not.toContain('<');
      expect(h.sends).toHaveLength(1);
      expect(h.sends[0]).toContain('Hello Sam');
    } finally {
      h.uninstall();
    }
  });

  it('harvests recipients only from the recipient field, not the draft body', async () => {
    const h = harness({}, ['Please contact attacker@example.com about this']);
    try {
      trustedClick(h.buttons[0]!);
      await flush();
      expect(h.encryptCalls[0]?.recipients).toEqual(['friend0@example.com']);
      expect(h.encryptCalls[0]?.recipients).not.toContain('attacker@example.com');
    } finally {
      h.uninstall();
    }
  });

  it('ignores untrusted synthetic clicks instead of encrypting on their behalf', async () => {
    const h = harness({ trusted: false });
    try {
      h.buttons[0]!.dispatchEvent(new MouseEvent('click', { bubbles: true, cancelable: true }));
      await flush();
      expect(h.encryptCalls).toHaveLength(0);
    } finally {
      h.uninstall();
    }
  });

  it('blocks the send when settings cannot be confirmed', async () => {
    const h = harness({ settingsFails: true }, ['Secret draft']);
    try {
      trustedClick(h.buttons[0]!);
      await flush();
      expect(h.encryptCalls).toHaveLength(0);
      expect(h.sends).toHaveLength(0);
      expect(document.querySelector('.newpgp-notice')?.textContent).toMatch(/nothing was sent/i);
    } finally {
      h.uninstall();
    }
  });

  it('routes Ctrl+Enter to the editor that received the key event', async () => {
    const h = harness({}, ['Secret A', 'Secret B']);
    try {
      h.editors[0]?.focus();
      const event = new KeyboardEvent('keydown', {
        key: 'Enter',
        ctrlKey: true,
        bubbles: true,
        cancelable: true,
      });
      h.editors[1]?.dispatchEvent(event);
      await flush();
      expect(h.encryptCalls[0]?.plaintext).toBe('Secret B');
    } finally {
      h.uninstall();
    }
  });
});
