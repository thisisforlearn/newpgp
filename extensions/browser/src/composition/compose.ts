// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Vaibhav - https://github.com/thisisforlearn
/**
 * The heart of the "send both" behaviour.
 *
 * The user's typed message is never deleted or replaced in the default mode:
 * we append the armored block underneath it. `pgp-only` is an explicit,
 * clearly labelled choice and is the only mode that replaces the draft.
 *
 * Safety invariant: without a complete, real PGP armor block this module
 * NEVER signals `applied: true`. The caller must stop the send instead of
 * silently falling back to plaintext.
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
  /** Why composition refused to apply, for a blocking, friendly notice. */
  reason?: 'missing-armor' | 'incomplete-armor';
}

const PGP_BEGIN = '-----BEGIN PGP ';
const PGP_END = '-----END PGP ';

/** True only for a string that contains a complete armor block. */
export function hasCompleteArmor(value: unknown): value is string {
  return typeof value === 'string' && value.includes(PGP_BEGIN) && value.includes(PGP_END);
}

function separatorFor(draft: string): string {
  if (/\n{2,}$/.test(draft)) return '';
  if (/\n$/.test(draft)) return '\n';
  return '\n\n';
}

export function composeBody(input: ComposeInput): ComposeResult {
  const draft = input.draft ?? '';
  const raw = input.armored;
  const armored = (typeof raw === 'string' ? raw : '').trim();

  // Never let an empty, whitespace-only, or garbage "armor" slip through:
  // sending the plain draft in PGP-only mode would leak the message.
  if (armored.length === 0) {
    return { body: draft, applied: false, mode: input.mode, reason: 'missing-armor' };
  }
  if (!hasCompleteArmor(armored)) {
    return { body: draft, applied: false, mode: input.mode, reason: 'incomplete-armor' };
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
