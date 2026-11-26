// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Vaibhav - https://github.com/thisisforlearn
import { describe, expect, it } from 'vitest';
import {
  FrameDecoder,
  FramingError,
  MAX_MESSAGE_SIZE,
  decodeFrameLength,
  encodeMessage,
  readUint32LE,
  writeUint32LE,
} from '../src/platform/framing';

describe('native messaging framing', () => {
  it('writes a 4-byte little-endian length prefix', () => {
    expect([...writeUint32LE(0x01020304)]).toEqual([0x04, 0x03, 0x02, 0x01]);
    expect(readUint32LE(new Uint8Array([0x04, 0x03, 0x02, 0x01]))).toBe(0x01020304);
  });

  it('encodes a JSON message with UTF-8 byte length', () => {
    const framed = encodeMessage({ id: '1', method: 'ping', params: { text: 'héllo' } });
    const expected = new TextEncoder().encode(JSON.stringify({ id: '1', method: 'ping', params: { text: 'héllo' } }));
    expect(readUint32LE(framed)).toBe(expected.byteLength);
    expect([...framed.slice(4)]).toEqual([...expected]);
    expect(decodeFrameLength(framed)).toBe(expected.byteLength);
  });

  it('rejects messages larger than 1 MiB', () => {
    const huge = 'x'.repeat(MAX_MESSAGE_SIZE);
    expect(() => encodeMessage({ huge })).toThrow(FramingError);
    expect(() => decodeFrameLength(new Uint8Array([0, 0, 0x11, 0x00]))).toThrow(/1 MiB/);
  });

  it('reassembles a message split byte by byte', () => {
    const framed = encodeMessage({ id: 'abc', ok: true, result: { value: 42 } });
    const decoder = new FrameDecoder();
    let decoded: unknown[] = [];
    for (const byte of framed) decoded = decoded.concat(decoder.push(new Uint8Array([byte])));
    expect(decoded).toEqual([{ id: 'abc', ok: true, result: { value: 42 } }]);
    expect(decoder.pending).toBe(0);
  });

  it('decodes several messages in one chunk and keeps a partial tail', () => {
    const decoder = new FrameDecoder();
    const first = encodeMessage({ id: '1' });
    const second = encodeMessage({ id: '2' });
    const third = encodeMessage({ id: '3' });
    const combined = new Uint8Array(first.length + second.length + third.length);
    combined.set(first, 0);
    combined.set(second, first.length);
    combined.set(third, first.length + second.length);

    const messages = decoder.push(combined.subarray(0, first.length + second.length + 2));
    expect(messages).toEqual([{ id: '1' }, { id: '2' }]);
    expect(decoder.pending).toBe(2);
    expect(decoder.push(combined.subarray(first.length + second.length + 2))).toEqual([{ id: '3' }]);
  });

  it('accepts ArrayBuffer chunks and resets cleanly', () => {
    const decoder = new FrameDecoder();
    const framed = encodeMessage({ hello: 'world' });
    expect(decoder.push(framed.buffer)).toEqual([{ hello: 'world' }]);
    decoder.reset();
    expect(decoder.pending).toBe(0);
  });

  it('rejects malformed JSON with a readable framing error', () => {
    const payload = new TextEncoder().encode('{not json');
    const framed = new Uint8Array(4 + payload.length);
    framed.set(writeUint32LE(payload.length), 0);
    framed.set(payload, 4);
    expect(() => new FrameDecoder().push(framed)).toThrow(FramingError);
  });

  it('recovers after an oversize header instead of staying poisoned', () => {
    const decoder = new FrameDecoder();
    const poison = new Uint8Array([0, 0, 0x11, 0x00]); // 1 MiB + 64 KiB
    expect(() => decoder.push(poison)).toThrow(FramingError);
    expect(decoder.pending).toBe(0);
    expect(decoder.push(encodeMessage({ id: 'after', ok: true }))).toEqual([{ id: 'after', ok: true }]);
  });

  it('drops the bad payload and decodes the next frame', () => {
    const decoder = new FrameDecoder();
    const bad = new TextEncoder().encode('{not json');
    const badFrame = new Uint8Array(4 + bad.length);
    badFrame.set(writeUint32LE(bad.length), 0);
    badFrame.set(bad, 4);
    expect(() => decoder.push(badFrame)).toThrow(FramingError);
    expect(decoder.pending).toBe(0);
    expect(decoder.push(encodeMessage({ healthy: true }))).toEqual([{ healthy: true }]);
  });

  it('rejects invalid UTF-8 in a frame', () => {
    const payload = new Uint8Array([0xff, 0xfe, 0x80]);
    const framed = new Uint8Array(4 + payload.length);
    framed.set(writeUint32LE(payload.length), 0);
    framed.set(payload, 4);
    expect(() => new FrameDecoder().push(framed)).toThrow(expect.objectContaining({ code: 'INVALID_JSON' }));
  });
});
