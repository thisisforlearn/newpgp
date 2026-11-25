// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Vaibhav - https://github.com/thisisforlearn
/** Detect armored PGP blocks in incoming mail and decrypt them in place. */

import { callBackground } from '../messages';
import { toFriendlyError } from '../native/errors';
import type { BrowserApi } from '../platform/browser';
import { buildDecryptView, renderDecryptedView, renderDecryptError } from '../mail/decryptView';
import type { DecryptResult } from '../types';

const ARMOR_MARKER = '-----BEGIN PGP MESSAGE-----';

export function findArmorTextNodes(root: ParentNode): Text[] {
  const walker = document.createTreeWalker(root as Node, NodeFilter.SHOW_TEXT, {
    acceptNode(node: Text): number {
      const text = node.textContent ?? '';
      if (!text.includes(ARMOR_MARKER)) return NodeFilter.FILTER_REJECT;
      const parent = node.parentElement;
      if (!parent) return NodeFilter.FILTER_REJECT;
      if (parent.closest('[data-newpgp-armor], script, style, [contenteditable="true"]')) {
        return NodeFilter.FILTER_REJECT;
      }
      return NodeFilter.FILTER_ACCEPT;
    },
  });

  const nodes: Text[] = [];
  let current = walker.nextNode();
  while (current) {
    nodes.push(current as Text);
    current = walker.nextNode();
  }
  return nodes;
}

/**
 * Installs a MutationObserver-driven scanner. Returns a cleanup function.
 * All rendered text uses `textContent`, so untrusted mail cannot inject HTML.
 */
export function installIncomingDecrypt(api: BrowserApi, root: ParentNode = document): () => void {
  const wrap = (node: Text): void => {
    const raw = node.textContent ?? '';
    if (!raw.includes(ARMOR_MARKER) || !node.parentNode) return;

    const wrapper = document.createElement('span');
    wrapper.className = 'newpgp-armor';
    wrapper.setAttribute('data-newpgp-armor', 'pending');

    const pre = document.createElement('pre');
    pre.className = 'newpgp-armor__text';
    pre.textContent = raw;

    const button = document.createElement('button');
    button.type = 'button';
    button.className = 'newpgp-decrypt-button';
    button.textContent = 'Decrypt with NewPGP';
    button.setAttribute('aria-label', 'Decrypt this encrypted message with NewPGP');

    wrapper.append(pre, button);
    node.parentNode.replaceChild(wrapper, node);

    const run = async (): Promise<void> => {
      button.disabled = true;
      button.textContent = 'Decrypting…';
      const response = await callBackground<DecryptResult>(api, {
        type: 'DECRYPT_ARMORED',
        armored: raw,
      });
      if (response.ok && response.result) {
        renderDecryptedView(wrapper, buildDecryptView(response.result));
        return;
      }
      renderDecryptError(wrapper, response.error ?? toFriendlyError(new Error('unknown')), () => {
        void run();
      });
    };

    button.addEventListener('click', () => {
      void run();
    });
  };

  const scan = (): void => {
    for (const node of findArmorTextNodes(root)) wrap(node);
  };

  scan();

  let timer: ReturnType<typeof setTimeout> | null = null;
  const observer = new MutationObserver(() => {
    if (timer !== null) clearTimeout(timer);
    timer = setTimeout(() => {
      timer = null;
      scan();
    }, 150);
  });
  observer.observe(root as Node, { childList: true, subtree: true });

  return () => {
    if (timer !== null) clearTimeout(timer);
    observer.disconnect();
  };
}
