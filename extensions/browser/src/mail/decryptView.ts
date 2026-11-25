// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Vaibhav - https://github.com/thisisforlearn
/** View model and DOM rendering for decrypted messages. */

import type { FriendlyError } from '../native/errors';
import type { DecryptResult, SignatureStatus } from '../types';

export type SignatureTone = 'good' | 'warn' | 'bad';

export interface DecryptView {
  plaintext: string;
  signatureLabel: string;
  tone: SignatureTone;
  hint: string | null;
  heading: string;
}

const SIGNATURE_COPY: Record<SignatureStatus, { label: string; tone: SignatureTone; hint: string | null }> = {
  verified: {
    label: 'Verified',
    tone: 'good',
    hint: 'The signature matches a key NewPGP knows. The text itself is still worth reading carefully.',
  },
  unverified: {
    label: 'Unverified — verify by comparing the safety number',
    tone: 'warn',
    hint: 'Compare the safety number with your contact through a different channel, such as a phone call.',
  },
  invalid: {
    label: 'Invalid',
    tone: 'bad',
    hint: 'The signature did not check out. Treat this message with caution and confirm it with the sender.',
  },
};

export function buildDecryptView(result: DecryptResult): DecryptView {
  const signature = SIGNATURE_COPY[result.signature] ?? SIGNATURE_COPY.unverified;
  return {
    plaintext: result.plaintext,
    signatureLabel: signature.label,
    tone: signature.tone,
    hint: signature.hint,
    heading: 'Decrypted with NewPGP',
  };
}

/** Render untrusted text via textContent only — never innerHTML. */
export function renderDecryptedView(container: HTMLElement, view: DecryptView): void {
  container.replaceChildren();
  container.classList.add('newpgp-decrypted');
  container.setAttribute('data-newpgp-armor', 'done');

  const heading = document.createElement('div');
  heading.className = 'newpgp-decrypted__heading';
  heading.textContent = view.heading;

  const status = document.createElement('div');
  status.className = `newpgp-status newpgp-status--${view.tone}`;
  status.setAttribute('role', 'status');
  status.textContent = view.signatureLabel;

  const text = document.createElement('pre');
  text.className = 'newpgp-decrypted__text';
  text.textContent = view.plaintext;

  container.append(heading, status, text);
  if (view.hint) {
    const hint = document.createElement('p');
    hint.className = 'newpgp-hint';
    hint.textContent = view.hint;
    container.append(hint);
  }
}

export function renderDecryptError(container: HTMLElement, error: FriendlyError, retry: () => void): void {
  container.replaceChildren();
  container.classList.add('newpgp-decrypted', 'newpgp-decrypted--error');
  container.setAttribute('data-newpgp-armor', 'done');

  const title = document.createElement('div');
  title.className = 'newpgp-decrypted__heading';
  title.textContent = error.title;

  const message = document.createElement('p');
  message.textContent = error.message;

  const button = document.createElement('button');
  button.type = 'button';
  button.className = 'newpgp-decrypt-button';
  button.textContent = 'Try again';
  button.addEventListener('click', retry);

  container.append(title, message, button);
  if (error.hint) {
    const hint = document.createElement('p');
    hint.className = 'newpgp-hint';
    hint.textContent = error.hint;
    container.append(hint);
  }
}
