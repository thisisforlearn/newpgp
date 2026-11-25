// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Vaibhav - https://github.com/thisisforlearn
/**
 * The heart of the "send both" behaviour.
 *
 * The user's typed message is never deleted or replaced in the default mode:
 * we append the armored block underneath it. `pgp-only` is an explicit,
 * clearly labelled choice and is the only mode that replaces the draft.
 */

import type { SendMode } from '../types';

export interface ComposeInput {
  /** Exactly what the user typed. Kept verbatim. */
  draft: string;
  /** Armored PGP block produced by the bridge. */
  armored: string;
  mode: SendMode;
}

export interface ComposeResult {
  body: string;
  /** True when an armored block was actually appended/inserted. */
  applied: boolean;
  mode: SendMode;
}

function separatorFor(draft: string): string {
  if (/\n{2,}$/.test(draft)) return '';
  if (/\n$/.test(draft)) return '\n';
  return '\n\n';
}

export function composeBody(input: ComposeInput): ComposeResult {
  const draft = input.draft ?? '';
  const armored = (input.armored ?? '').trim();

  // If encryption did not produce a block, leave the user's words untouched.
  if (armored.length === 0) {
    return { body: draft, applied: false, mode: input.mode };
  }

  if (input.mode === 'pgp-only') {
    return { body: armored, applied: true, mode: 'pgp-only' };
  }

  return {
    body: `${draft}${separatorFor(draft)}${armored}`,
    applied: true,
    mode: 'send-both',
  };
}

/** Human-readable description used by the popup and options UI. */
export function describeMode(mode: SendMode): { label: string; description: string } {
  return mode === 'send-both'
    ? {
        label: 'Send both (recommended)',
        description:
          'Your normal message stays exactly as you wrote it, with an encrypted copy underneath. Anyone can read it; people using PGP get encryption too.',
      }
    : {
        label: 'PGP only',
        description:
          'Only the encrypted block is sent. People who do not use PGP will not be able to read your message.',
      };
}
