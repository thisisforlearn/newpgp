// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Vaibhav - https://github.com/thisisforlearn
/**
 * Every error shown to the user passes through here. Bridge codes and native
 * messaging internals are deliberately translated into plain language.
 */

import type { BridgeErrorCode, BridgeErrorShape } from '../types';

export interface FriendlyError {
  title: string;
  message: string;
  hint?: string;
}

const COPY: Record<BridgeErrorCode, FriendlyError> = {
  NATIVE_HOST_NOT_FOUND: {
    title: "The NewPGP helper isn't installed",
    message: 'We could not find the NewPGP helper on this computer, so no encryption happened.',
    hint: 'Install the NewPGP desktop app (or CLI daemon), then reload the extension. Your message is still here.',
  },
  BRIDGE_DISCONNECTED: {
    title: 'The NewPGP helper disconnected',
    message: 'The connection to the NewPGP helper was lost before the job finished.',
    hint: 'Check that the NewPGP app is running, then try again. Your draft has not been changed.',
  },
  BAD_REQUEST: {
    title: 'We could not complete that request',
    message: 'The NewPGP helper did not understand the request.',
    hint: 'Try again. If it keeps happening, restart the NewPGP app.',
  },
  NO_KEY: {
    title: 'No public key found',
    message: 'We could not find a public key for this email address.',
    hint: 'Ask your contact to publish their key via WKD, keys.openpgp.org, or Autocrypt, or paste their key manually in the NewPGP app.',
  },
  AMBIGUOUS_KEY: {
    title: 'More than one key was found',
    message: 'We found several public keys for this address and do not know which one to use.',
    hint: 'Ask your contact which key is current, or choose it manually in the NewPGP app.',
  },
  DECRYPT_FAILED: {
    title: "We couldn't decrypt this message",
    message: 'The encrypted block was found, but it could not be opened with your keys.',
    hint: 'Make sure you are using the right account and that the whole block was copied.',
  },
  INVALID_ARMOR: {
    title: 'This does not look like a complete PGP block',
    message: 'Something is missing from the encrypted block, so we left it alone.',
    hint: 'Check that the copy starts with “BEGIN PGP MESSAGE” and ends with “END PGP MESSAGE”.',
  },
  TIMEOUT: {
    title: 'The NewPGP helper took too long',
    message: 'We stopped waiting for the helper so your browser stays responsive.',
    hint: 'Your draft is untouched. Try again in a moment.',
  },
  UNSUPPORTED: {
    title: 'This feature is not available yet',
    message: 'The installed NewPGP helper does not support this action.',
    hint: 'Update the NewPGP desktop app to the latest version.',
  },
  UNKNOWN: {
    title: 'Something went wrong',
    message: 'NewPGP could not finish this action.',
    hint: 'Try again. If it keeps happening, restart the NewPGP app and reload the extension.',
  },
};

export function isBridgeErrorShape(value: unknown): value is BridgeErrorShape {
  if (typeof value !== 'object' || value === null) return false;
  const candidate = value as Partial<BridgeErrorShape>;
  return typeof candidate.code === 'string' && typeof candidate.message === 'string';
}

function codeFromUnknown(error: unknown): BridgeErrorCode {
  if (isBridgeErrorShape(error) && error.code in COPY) return error.code;

  const message = error instanceof Error ? error.message : typeof error === 'string' ? error : '';
  const lower = message.toLowerCase();
  if (lower.includes('native') && lower.includes('not found')) return 'NATIVE_HOST_NOT_FOUND';
  if (lower.includes('disconnect') || lower.includes('closed')) return 'BRIDGE_DISCONNECTED';
  if (lower.includes('timed out') || lower.includes('timeout')) return 'TIMEOUT';
  if (lower.includes('armor')) return 'INVALID_ARMOR';
  if (lower.includes('decrypt')) return 'DECRYPT_FAILED';
  if (lower.includes('key')) return 'NO_KEY';
  return 'UNKNOWN';
}

/** Convert any thrown value into a friendly, displayable error. */
export function toFriendlyError(error: unknown): FriendlyError {
  return COPY[codeFromUnknown(error)];
}

export function friendlyMessage(error: unknown): string {
  const friendly = toFriendlyError(error);
  return [friendly.title, friendly.message, friendly.hint].filter(Boolean).join(' ');
}

/** Construct a typed bridge error for native-host implementations. */
export function bridgeError(code: BridgeErrorCode, message: string = code): BridgeErrorShape {
  return { code, message };
}
