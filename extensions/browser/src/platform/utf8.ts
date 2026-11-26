// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Vaibhav - https://github.com/thisisforlearn
/** Small UTF-8 helpers that work in browsers, Node and jsdom alike. */

export function utf8Encode(text: string): Uint8Array {
  if (typeof TextEncoder !== 'undefined') {
    return new TextEncoder().encode(text);
  }
  const escaped = unescape(encodeURIComponent(text));
  const bytes = new Uint8Array(escaped.length);
  for (let i = 0; i < escaped.length; i += 1) {
    bytes[i] = escaped.charCodeAt(i) & 0xff;
  }
  return bytes;
}

export function utf8Decode(bytes: Uint8Array, fatal = false): string {
  if (typeof TextDecoder !== 'undefined') {
    return new TextDecoder('utf-8', { fatal }).decode(bytes);
  }
  let escaped = '';
  for (const byte of bytes) {
    escaped += `%${byte.toString(16).padStart(2, '0')}`;
  }
  try {
    return decodeURIComponent(escaped);
  } catch {
    if (fatal) throw new Error('Invalid UTF-8 sequence in native message.');
    return '';
  }
}
