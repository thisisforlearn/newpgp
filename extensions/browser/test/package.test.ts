// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Vaibhav - https://github.com/thisisforlearn
import { mkdtempSync, mkdirSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { basename, join } from 'node:path';
import { inflateRawSync } from 'node:zlib';
import { afterEach, describe, expect, it } from 'vitest';
import { createZip, packageExtension } from '../scripts/package.mjs';

const dirs: string[] = [];

afterEach(() => {
  while (dirs.length) rmSync(dirs.pop()!, { recursive: true, force: true });
});

function fixture(): { root: string; dist: string } {
  const root = mkdtempSync(join(tmpdir(), 'newpgp-package-'));
  dirs.push(root);
  const dist = join(root, 'dist');
  mkdirSync(join(dist, 'ui'), { recursive: true });
  writeFileSync(join(dist, 'manifest.json'), JSON.stringify({ manifest_version: 3, version: '9.8.7' }));
  writeFileSync(join(dist, 'popup.html'), '<!doctype html>');
  writeFileSync(join(dist, 'ui', 'popup.js'), 'console.log(1);');
  writeFileSync(join(dist, 'icon128.png'), new Uint8Array([0x89, 0x50, 0x4e, 0x47]));
  return { root, dist };
}

function archiveEntries(bytes: Uint8Array): Map<string, Uint8Array> {
  const buf = Buffer.from(bytes);
  let eocd = -1;
  for (let i = buf.length - 22; i >= 0; i -= 1) {
    if (buf.readUInt32LE(i) === 0x06054b50) {
      eocd = i;
      break;
    }
  }
  expect(eocd).toBeGreaterThanOrEqual(0);
  const count = buf.readUInt16LE(eocd! + 10);
  expect(count).toBeGreaterThan(0);
  let cursor = buf.readUInt32LE(eocd! + 16);
  const entries = new Map<string, Uint8Array>();
  for (let i = 0; i < count; i += 1) {
    const nameLength = buf.readUInt16LE(cursor + 28);
    const extraLength = buf.readUInt16LE(cursor + 30);
    const commentLength = buf.readUInt16LE(cursor + 32);
    const localOffset = buf.readUInt32LE(cursor + 42);
    const name = buf.subarray(cursor + 46, cursor + 46 + nameLength).toString('utf8');
    expect(buf.readUInt32LE(localOffset)).toBe(0x04034b50);
    const localNameLength = buf.readUInt16LE(localOffset + 26);
    const localExtraLength = buf.readUInt16LE(localOffset + 28);
    const compressedSize = buf.readUInt32LE(localOffset + 18);
    const start = localOffset + 30 + localNameLength + localExtraLength;
    entries.set(name, buf.subarray(start, start + compressedSize));
    cursor += 46 + nameLength + extraLength + commentLength;
  }
  return entries;
}

describe('release packaging', () => {
  it('produces versioned ZIP and XPI from dist only', () => {
    const { root, dist } = fixture();
    const result = packageExtension({ distDir: dist, outDir: root });

    expect(result.version).toBe('9.8.7');
    expect(basename(result.zipPath)).toBe('newpgp-browser-9.8.7.zip');
    expect(basename(result.xpiPath)).toBe('newpgp-browser-9.8.7.xpi');
    expect(readFileSync(result.zipPath)).toEqual(readFileSync(result.xpiPath));
    expect(result.files).toEqual(['icon128.png', 'manifest.json', 'popup.html', 'ui/popup.js']);
    expect(result.files.some((name) => name.includes('src/') || name.includes('node_modules'))).toBe(false);
  });

  it('writes a valid archive with manifest.json at its root', () => {
    const { root, dist } = fixture();
    const result = packageExtension({ distDir: dist, outDir: root });
    const entries = archiveEntries(readFileSync(result.zipPath));

    expect([...entries.keys()].sort()).toEqual(['icon128.png', 'manifest.json', 'popup.html', 'ui/popup.js']);
    const manifest = inflateRawSync(Buffer.from(entries.get('manifest.json')!)).toString('utf8');
    expect(JSON.parse(manifest).version).toBe('9.8.7');
  });

  it('rejects path traversal and missing dist', () => {
    expect(() => createZip([{ name: '../evil.txt', data: new Uint8Array([1]) }])).toThrow(/Unsafe archive/);
    expect(() => packageExtension({ distDir: join(tmpdir(), 'newpgp-does-not-exist') })).toThrow(/Build/);
  });
});
