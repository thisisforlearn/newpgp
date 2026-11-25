// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Vaibhav - https://github.com/thisisforlearn
/**
 * Chrome native messaging wire framing.
 *
 * The wire format is: a 4-byte little-endian unsigned length followed by that
 * many bytes of UTF-8 JSON. Chromium/Firefox apply this framing for us when we
 * use `runtime.connectNative`, but implementing it explicitly here gives us a
 * testable protocol (and lets us talk to raw byte transports, e.g. in tests or
 * a future local socket bridge).
 *
 * @see https://developer.chrome.com/docs/apps/native-messaging#native-messaging-protocol
 */

import { utf8Decode, utf8Encode } from './utf8';

/** Chrome caps a single native message at 1 MiB. */
export const MAX_MESSAGE_SIZE = 1024 * 1024;

export type FramingErrorCode = 'MESSAGE_TOO_LARGE' | 'INVALID_HEADER' | 'INVALID_JSON' | 'INVALID_CHUNK';

export class FramingError extends Error {
  readonly code: FramingErrorCode;

  constructor(code: FramingErrorCode, message: string) {
    super(message);
    this.name = 'FramingError';
    this.code = code;
  }
}

export function writeUint32LE(value: number): Uint8Array {
  if (!Number.isInteger(value) || value < 0 || value > 0xffffffff) {
    throw new FramingError('INVALID_HEADER', 'Message length is out of range.');
  }
  return new Uint8Array([value & 0xff, (value >>> 8) & 0xff, (value >>> 16) & 0xff, (value >>> 24) & 0xff]);
}

export function readUint32LE(bytes: Uint8Array, offset = 0): number {
  if (bytes.byteLength < offset + 4) {
    throw new FramingError('INVALID_HEADER', 'Native message header must be 4 bytes.');
  }
  return (
    (bytes[offset] ?? 0) |
    ((bytes[offset + 1] ?? 0) << 8) |
    ((bytes[offset + 2] ?? 0) << 16) |
    ((bytes[offset + 3] ?? 0) << 24)
  ) >>> 0;
}

/** Serialize a JSON-compatible value into one framed native message. */
export function encodeMessage(message: unknown): Uint8Array {
  const payload = utf8Encode(JSON.stringify(message));
  if (payload.byteLength > MAX_MESSAGE_SIZE) {
    throw new FramingError('MESSAGE_TOO_LARGE', 'Native message exceeds the 1 MiB limit.');
  }
  const framed = new Uint8Array(4 + payload.byteLength);
  framed.set(writeUint32LE(payload.byteLength), 0);
  framed.set(payload, 4);
  return framed;
}

/** Validate just the length prefix without waiting for the whole payload. */
export function decodeFrameLength(header: Uint8Array): number {
  const length = readUint32LE(header);
  if (length > MAX_MESSAGE_SIZE) {
    throw new FramingError('MESSAGE_TOO_LARGE', 'Native message exceeds the 1 MiB limit.');
  }
  return length;
}

function toBytes(chunk: unknown): Uint8Array {
  if (chunk instanceof Uint8Array) return chunk;
  if (chunk instanceof ArrayBuffer) return new Uint8Array(chunk);
  if (ArrayBuffer.isView(chunk)) {
    return new Uint8Array(chunk.buffer, chunk.byteOffset, chunk.byteLength);
  }
  throw new FramingError('INVALID_CHUNK', 'Native message chunk is not binary data.');
}

/**
 * Incremental decoder. `push` accepts arbitrarily split chunks and returns
 * every complete JSON message found so far.
 */
export class FrameDecoder {
  private buffer: Uint8Array = new Uint8Array(0);

  push(chunk: unknown): unknown[] {
    const bytes = toBytes(chunk);
    if (bytes.byteLength === 0) return [];

    const merged = new Uint8Array(this.buffer.byteLength + bytes.byteLength);
    merged.set(this.buffer, 0);
    merged.set(bytes, this.buffer.byteLength);
    this.buffer = merged;

    const messages: unknown[] = [];
    while (this.buffer.byteLength >= 4) {
      const length = decodeFrameLength(this.buffer.subarray(0, 4));
      const total = 4 + length;
      if (this.buffer.byteLength < total) break;

      const payload = this.buffer.subarray(4, total);
      let parsed: unknown;
      try {
        parsed = JSON.parse(utf8Decode(payload));
      } catch {
        throw new FramingError('INVALID_JSON', 'Native host sent malformed JSON.');
      }
      messages.push(parsed);
      this.buffer = this.buffer.slice(total);
    }
    return messages;
  }

  /** Number of buffered bytes belonging to an incomplete message. */
  get pending(): number {
    return this.buffer.byteLength;
  }

  reset(): void {
    this.buffer = new Uint8Array(0);
  }
}
