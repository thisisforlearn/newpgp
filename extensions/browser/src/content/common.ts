// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Vaibhav - https://github.com/thisisforlearn
/**
 * Best-effort compose interception shared by Gmail and Outlook content
 * scripts. Webmail markup changes often, so selectors are configurable per
 * provider. The draft never leaves memory: we read it from the editor, send it
 * to the background worker, and write the composed body straight back.
 *
 * Security invariants:
 * - The editor is resolved from the clicked button, never from a stale "active"
 *   compose window. A second compose window cannot borrow the first one's text.
 * - Only our own explicitly marked re-dispatched event may pass the interceptor;
 *   all other synthetic events are ignored.
 * - If no complete armor block comes back, the send is BLOCKED. We never write
 *   the draft back and re-send as if encryption had succeeded.
 */

import { composeBody, type ComposeResult } from '../composition/compose';
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
  /**
   * Test seam only. Defaults to the browser's real `event.isTrusted`; tests
   * inject a predicate because jsdom hard-codes that property to false.
   */
  isTrustedEvent?: (event: Event) => boolean;
}

interface EncryptReply {
  armored: string;
  sendMode: SendMode;
}

const EMAIL_RE = /[A-Z0-9._%+-]+@[A-Z0-9.-]+\.[A-Z]{2,}/gi;
const MAX_RECIPIENTS = 50;
const ARMOR_CLASS = 'newpgp-armor';
const NOTICE_CLASS = 'newpgp-notice';

/** Events we re-dispatch ourselves, marked so the interceptor lets them through. */
const ownSendEvents = new WeakSet<Event>();

function markOwnSend(event: Event): Event {
  ownSendEvents.add(event);
  return event;
}

function isOwnSend(event: Event): boolean {
  if (!ownSendEvents.has(event)) return false;
  ownSendEvents.delete(event); // one shot
  return true;
}

/**
 * Ignore injected clicks from page scripts. Our own re-dispatched send event is
 * marked and is allowed to reach the provider untouched; it is consumed here
 * so it can never be intercepted a second time.
 */
function shouldIntercept(event: Event, isTrusted: (event: Event) => boolean): boolean {
  if (isOwnSend(event)) return false;
  return isTrusted(event);
}

function closestAny(element: Element, selectors: string[]): Element | null {
  for (const selector of selectors) {
    try {
      const found = element.closest(selector);
      if (found) return found;
    } catch {
      // Ignore provider-specific selectors this DOM does not understand.
    }
  }
  return null;
}

function queryEditor(scope: ParentNode, selectors: string[]): HTMLElement | null {
  for (const selector of selectors) {
    try {
      const found = scope.querySelector<HTMLElement>(selector);
      if (found) return found;
    } catch {
      // Same as above: a stale selector must not break the whole interceptor.
    }
  }
  return null;
}

/**
 * Resolve the editor relative to the clicked send button. Walk up from the
 * button until the nearest container that actually holds an editor, so two
 * open compose windows can never be crossed.
 */
function editorForButton(button: Element, editorSelectors: string[]): HTMLElement | null {
  let node: Element | null = button;
  while (node) {
    const editor = queryEditor(node, editorSelectors);
    if (editor) return editor;
    node = node.parentElement;
  }
  return null;
}

function editorFromTarget(target: EventTarget | null, editorSelectors: string[]): HTMLElement | null {
  if (!(target instanceof Element)) return null;
  return closestAny(target, editorSelectors) as HTMLElement | null;
}

/**
 * Recipients come ONLY from the configured recipient fields inside the compose
 * container. We deliberately do not scrape the whole form: addresses quoted in
 * the draft body must never become encryption recipients.
 */
function collectRecipients(scope: ParentNode, selectors: string[], editor: HTMLElement): string[] {
  const found = new Set<string>();
  for (const selector of selectors) {
    let nodes: NodeListOf<Element>;
    try {
      nodes = scope.querySelectorAll<Element>(selector);
    } catch {
      continue;
    }
    nodes.forEach((node) => {
      if (found.size >= MAX_RECIPIENTS) return;
      if (editor.contains(node)) return;
      if (node.closest(`.${ARMOR_CLASS}, .${NOTICE_CLASS}`)) return;
      const value =
        node instanceof HTMLInputElement || node instanceof HTMLTextAreaElement
          ? node.value
          : (node.textContent ?? '');
      for (const address of value.match(EMAIL_RE) ?? []) {
        if (found.size >= MAX_RECIPIENTS) break;
        found.add(address.toLowerCase());
      }
    });
  }
  return [...found];
}

/** Draft text excludes armor/notice nodes we appended on an earlier attempt. */
function readDraft(editor: HTMLElement): string {
  const clone = editor.cloneNode(true) as HTMLElement;
  clone.querySelectorAll(`.${ARMOR_CLASS}, .${NOTICE_CLASS}`).forEach((node) => node.remove());
  return clone.innerText || clone.textContent || '';
}

function armorNode(armored: string): HTMLElement {
  const pre = document.createElement('pre');
  pre.className = ARMOR_CLASS;
  // textContent only. Provider HTML is never re-parsed from a string.
  pre.textContent = armored;
  return pre;
}

/**
 * Apply the composed body without destroying rich text. Send-both appends a
 * sanitized <pre>; PGP-only replaces the children with exactly one <pre>.
 */
function applyComposed(editor: HTMLElement, composed: ComposeResult, armored: string): void {
  editor.querySelectorAll(`.${ARMOR_CLASS}`).forEach((node) => node.remove());
  if (composed.mode === 'pgp-only') {
    editor.replaceChildren(armorNode(armored));
  } else {
    editor.append(armorNode(`\n\n${armored}`));
  }
  editor.dispatchEvent(new Event('input', { bubbles: true }));
}

function showNotice(editor: HTMLElement, text: string): void {
  const notice = document.createElement('div');
  notice.className = NOTICE_CLASS;
  notice.setAttribute('role', 'alert');
  notice.textContent = text;
  const host = editor.parentElement ?? editor;
  host.append(notice);
  setTimeout(() => notice.remove(), 10_000);
}

export function installComposeInterceptor(options: ComposeInterceptorOptions): () => void {
  const { api, selectors, root = document } = options;
  const inFlight = new WeakSet<HTMLElement>();
  const isTrusted = options.isTrustedEvent ?? ((event: Event) => event.isTrusted);

  const sendNow = (button: Element | null, editor: HTMLElement): void => {
    if (button) {
      button.dispatchEvent(markOwnSend(new MouseEvent('click', { bubbles: true, cancelable: true })));
      return;
    }
    editor.dispatchEvent(
      markOwnSend(
        new KeyboardEvent('keydown', {
          key: 'Enter',
          ctrlKey: true,
          bubbles: true,
          cancelable: true,
        }),
      ),
    );
  };

  const intercept = async (editor: HTMLElement, event: Event, button: Element | null): Promise<void> => {
    if (!shouldIntercept(event, isTrusted)) return;
    if (inFlight.has(editor)) return;
    event.preventDefault();
    event.stopPropagation();
    inFlight.add(editor);

    try {
      const draft = readDraft(editor);
      const scope: ParentNode = editor.closest('form, [role="dialog"]') ?? editor.parentElement ?? document;
      const recipients = collectRecipients(scope, selectors.recipients, editor);

      const settingsResult = await callBackground<Settings>(api, { type: 'GET_SETTINGS' });
      if (!settingsResult.ok || !settingsResult.result) {
        showNotice(
          editor,
          'We could not confirm your NewPGP settings, so nothing was sent. Please reload the extension and try again.',
        );
        return;
      }
      const settings = settingsResult.result;

      // NewPGP is paused: let the user's own click through, untouched.
      if (!settings.enabled) {
        sendNow(button, editor);
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
          friendly
            ? `${friendly.title} ${friendly.message} Nothing was sent.`
            : 'NewPGP could not encrypt this message. Nothing was sent.',
        );
        return;
      }

      const armored = (result.result.armored ?? '').trim();
      const composed = composeBody({ draft, armored, mode: settings.sendMode });
      if (!composed.applied) {
        // BLOCKING PATH: no armor means no send, in either mode.
        showNotice(
          editor,
          composed.mode === 'pgp-only'
            ? 'NewPGP could not produce an encrypted block, so nothing was sent. Your message was left untouched.'
            : 'NewPGP could not produce an encrypted block, so nothing was sent. Your message was left untouched.',
        );
        return;
      }

      applyComposed(editor, composed, armored);
      sendNow(button, editor);
    } finally {
      inFlight.delete(editor);
    }
  };

  const onKeyDown = (event: KeyboardEvent): void => {
    if (!shouldIntercept(event, isTrusted)) return;
    if (!(event.ctrlKey || event.metaKey) || event.key !== 'Enter') return;
    const editor = editorFromTarget(event.target, selectors.editor);
    if (!editor) return;
    void intercept(editor, event, null);
  };

  const onClick = (event: MouseEvent): void => {
    if (!shouldIntercept(event, isTrusted)) return;
    const target = event.target;
    if (!(target instanceof Element)) return;
    const button = closestAny(target, selectors.sendButton);
    if (!button) return;
    const editor = editorForButton(button, selectors.editor);
    if (!editor) return;
    void intercept(editor, event, button);
  };

  // Capture phase so we can stop the provider's own send handler in time.
  document.addEventListener('keydown', onKeyDown, true);
  document.addEventListener('click', onClick, true);

  return () => {
    document.removeEventListener('keydown', onKeyDown, true);
    document.removeEventListener('click', onClick, true);
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
