// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Vaibhav - https://github.com/thisisforlearn
/** Minimal base64 helpers used by MockBridge (no Node Buffer dependency). */

import { utf8Decode, utf8Encode } from '../platform/utf8';

const ALPHABET = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/';

export function base64Encode(text: string): string {
  const bytes = utf8Encode(text);
  let output = '';
  for (let i = 0; i < bytes.length; i += 3) {
    const a = bytes[i] ?? 0;
    const b = bytes[i + 1] ?? 0;
    const c = bytes[i + 2] ?? 0;
    const value = (a << 16) | (b << 8) | c;
    output += ALPHABET[(value >>> 18) & 0x3f];
    output += ALPHABET[(value >>> 12) & 0x3f];
    output += i + 1 < bytes.length ? ALPHABET[(value >>> 6) & 0x3f] : '=';
    output += i + 2 < bytes.length ? ALPHABET[value & 0x3f] : '=';
  }
  return output;
}

export function base64Decode(encoded: string): string {
  const bytes: number[] = [];
  let buffer = 0;
  let bits = 0;
  for (const char of encoded) {
    if (char === '=') break;
    const index = ALPHABET.indexOf(char);
    if (index === -1) continue;
    buffer = (buffer << 6) | index;
    bits += 6;
    if (bits >= 8) {
      bits -= 8;
      bytes.push((buffer >>> bits) & 0xff);
    }
  }
  return utf8Decode(new Uint8Array(bytes));
}
