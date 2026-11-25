// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Vaibhav - https://github.com/thisisforlearn
/**
 * A short, human-comparable rendering of a fingerprint. This is a *display
 * aid*, not a replacement for comparing the full fingerprint. It is derived
 * deterministically so the popup and the native host agree.
 */

function fnv1a(input: string): number {
  let hash = 0x811c9dc5;
  for (let i = 0; i < input.length; i += 1) {
    hash ^= input.charCodeAt(i);
    hash = Math.imul(hash, 0x01000193);
  }
  return hash >>> 0;
}

export function safetyNumberFromFingerprint(fingerprint: string): string {
  const canonical = fingerprint.toUpperCase().replace(/[^0-9A-F]/g, '');
  return [0, 1, 2, 3]
    .map((index) => (fnv1a(`${index}:${canonical}`) % 10000).toString().padStart(4, '0'))
    .join(' ');
}

export function formatSafetyNumber(value: string): string {
  return value.replace(/\s+/g, ' ').trim();
}
