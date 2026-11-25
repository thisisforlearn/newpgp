// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Vaibhav - https://github.com/thisisforlearn
/**
 * Best-effort compose interception shared by Gmail and Outlook content
 * scripts. Webmail markup changes often, so selectors are configurable per
 * provider. The draft never leaves memory: we read it from the editor, send it
 * to the background worker, and write the composed body straight back.
 */

import { composeBody } from '../composition/compose';
import { callBackground, type RuntimeResult } from '../messages';
import type { BrowserApi, NativePort } from '../platform/browser';
import type { SendMode, Settings } from '../types';

export interface ComposeSelectors {
  editor: string[];
  sendButton: string[];
  recipients: string[];
}

export interface ComposeInterceptorOptions {
  api: BrowserApi;
  selectors: ComposeSelectors;
  root?: ParentNode;
}

interface EncryptReply {
  armored: string;
  sendMode: SendMode;
}

const EMAIL_RE = /[A-Z0-9._%+-]+@[A-Z0-9.-]+\.[A-Z]{2,}/gi;
const EMAIL_TEST_RE = /[A-Z0-9._%+-]+@[A-Z0-9.-]+\.[A-Z]{2,}/i;

function matchesAny(element: Element, selectors: string[]): boolean {
  return selectors.some((selector) => {
    try {
      return element.matches(selector);
    } catch {
      return false;
    }
  });
}

function collectRecipients(root: ParentNode, selectors: string[]): string[] {
  const found = new Set<string>();
  const nodes = root.querySelectorAll('input, [aria-label], [data-tooltip], [contenteditable="true"]');
  nodes.forEach((node) => {
    const textual = node instanceof HTMLInputElement ? node.value : (node.textContent ?? '');
    const label = node.getAttribute('aria-label') ?? node.getAttribute('data-tooltip') ?? '';
    if (!/(to|recipient|cc|bcc)/i.test(label) && !EMAIL_TEST_RE.test(textual)) return;
    const values = textual.match(EMAIL_RE) ?? [];
    for (const value of values) found.add(value.toLowerCase());
  });
  return [...found];
}

function showNotice(editor: HTMLElement, text: string): void {
  const notice = document.createElement('div');
  notice.className = 'newpgp-notice';
  notice.setAttribute('role', 'alert');
  notice.textContent = text;
  const host = editor.parentElement ?? editor;
  host.append(notice);
  setTimeout(() => notice.remove(), 10_000);
}

export function installComposeInterceptor(options: ComposeInterceptorOptions): () => void {
  const { api, selectors, root = document } = options;
  const pending = new WeakSet<HTMLElement>();
  const bypass = new WeakSet<HTMLElement>();
  let activeEditor: HTMLElement | null = null;

  const findEditors = (): HTMLElement[] => {
    const found: HTMLElement[] = [];
    const selector = selectors.editor.join(', ');
    if (selector.length === 0) return found;
    root.querySelectorAll<HTMLElement>(selector).forEach((element) => found.push(element));
    return found;
  };

  const intercept = async (editor: HTMLElement, event: Event, button: Element | null): Promise<void> => {
    if (bypass.has(editor)) {
      bypass.delete(editor);
      return;
    }
    event.preventDefault();
    event.stopPropagation();
    if (pending.has(editor)) return;
    pending.add(editor);

    try {
      const draft = editor.innerText || editor.textContent || '';
      const scope = editor.closest('form, [role="dialog"]') ?? document;
      const recipients = collectRecipients(scope, selectors.recipients);

      const settingsResult = await callBackground<Settings>(api, { type: 'GET_SETTINGS' });
      const settings: Settings = settingsResult.ok && settingsResult.result ? settingsResult.result : {
        sendMode: 'send-both',
        enabled: true,
        autosaveAcknowledged: false,
      };

      if (!settings.enabled) {
        bypass.add(editor);
        resend();
        return;
      }

      const result = await callBackground<EncryptReply>(api, {
        type: 'ENCRYPT',
        plaintext: draft,
        recipients,
      });
      if (!result.ok || !result.result) {
        const friendly = result.error;
        showNotice(
          editor,
          friendly ? `${friendly.title} ${friendly.message}` : 'NewPGP could not encrypt this message.',
        );
        return;
      }

      const composed = composeBody({
        draft,
        armored: result.result.armored,
        mode: settings.sendMode,
      });
      editor.textContent = composed.body;
      editor.dispatchEvent(new Event('input', { bubbles: true }));
      bypass.add(editor);
      resend();
    } finally {
      pending.delete(editor);
    }

    function resend(): void {
      if (button) {
        button.dispatchEvent(new MouseEvent('click', { bubbles: true, cancelable: true }));
        return;
      }
      editor.dispatchEvent(
        new KeyboardEvent('keydown', { key: 'Enter', ctrlKey: true, bubbles: true, cancelable: true }),
      );
    }
  };

  const onFocusIn = (event: FocusEvent): void => {
    const target = event.target;
    if (target instanceof Element) {
      const editor = target.closest<HTMLElement>(selectors.editor.join(', '));
      if (editor) activeEditor = editor;
    }
  };

  const onKeyDown = (event: KeyboardEvent): void => {
    if (!(event.ctrlKey || event.metaKey) || event.key !== 'Enter') return;
    const editor = event.target instanceof Element ? event.target.closest<HTMLElement>(selectors.editor.join(', ')) : null;
    if (!editor) return;
    void intercept(editor, event, null);
  };

  const onClick = (event: MouseEvent): void => {
    const target = event.target;
    if (!(target instanceof Element)) return;
    const button = target.closest(selectors.sendButton.join(', '));
    if (!button) return;
    const editor = activeEditor ?? findEditors()[0] ?? null;
    if (!editor) return;
    void intercept(editor, event, button);
  };

  document.addEventListener('focusin', onFocusIn, true);
  document.addEventListener('keydown', onKeyDown, true);
  document.addEventListener('click', onClick, true);

  const observer = new MutationObserver(() => {
    // Editors are created and destroyed as users open/close compose windows;
    // there is nothing to attach per node, we just keep `activeEditor` fresh.
    if (activeEditor && !activeEditor.isConnected) activeEditor = findEditors()[0] ?? null;
  });
  observer.observe(root, { childList: true, subtree: true });

  return () => {
    document.removeEventListener('focusin', onFocusIn, true);
    document.removeEventListener('keydown', onKeyDown, true);
    document.removeEventListener('click', onClick, true);
    observer.disconnect();
  };
}

/** Inject the scoped stylesheet once per webmail tab. */
export function ensureContentStyles(api: BrowserApi): void {
  if (document.getElementById('newpgp-content-styles')) return;
  const link = document.createElement('link');
  link.id = 'newpgp-content-styles';
  link.rel = 'stylesheet';
  link.href = api.runtime.getURL('ui/content.css');
  (document.head ?? document.documentElement).append(link);
}

/** Exported for tests that assert the exact shape of injected selectors. */
export function gmailSelectors(): ComposeSelectors {
  return {
    editor: ['div[contenteditable="true"][role="textbox"]', 'div[aria-label="Message Body"]'],
    sendButton: ['div[aria-label*="Send"]', 'button[name="send"]', '[data-tooltip*="Send"]'],
    recipients: ['[aria-label*="To"]', '[data-tooltip*="To"]'],
  };
}

export function outlookSelectors(): ComposeSelectors {
  return {
    editor: ['div[contenteditable="true"][aria-label*="Message"]', 'div[role="textbox"][contenteditable="true"]'],
    sendButton: ['button[aria-label*="Send"]', '[data-icon-name="Send"]', '[id*="Send"]'],
    recipients: ['[aria-label*="To"]', '[aria-label*="Cc"]', '[aria-label*="Bcc"]'],
  };
}

// Keep the NativePort type imported for downstream consumers that extend the
// transport without pulling in the whole browser shim.
export type { NativePort };
