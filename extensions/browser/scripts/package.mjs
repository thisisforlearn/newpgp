// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Vaibhav - https://github.com/thisisforlearn
/**
 * Cross-platform release packaging. Produces:
 *   newpgp-browser-<version>.zip  (Chrome Web Store / unpacked distribution)
 *   newpgp-browser-<version>.xpi  (Firefox self-distribution; an XPI is a ZIP)
 *
 * Only files under `dist/` are included, with manifest.json at the archive
 * root. No external `zip`/PowerShell dependency and no plaintext sources.
 */

import { readdirSync, readFileSync, writeFileSync, mkdirSync, existsSync } from 'node:fs';
import { basename, dirname, join, resolve } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { deflateRawSync } from 'node:zlib';
import { crc32 } from './icons.mjs';

const utf8 = new TextEncoder();

/**
 * Minimal, deterministic ZIP writer (deflate, no data descriptors). Returns a
 * complete archive as bytes so tests can inspect it without spawning a shell.
 */
export function createZip(files) {
  const localParts = [];
  const centralParts = [];
  let offset = 0;

  for (const file of files) {
    if (file.name.includes('\\') || file.name.startsWith('/') || file.name.split('/').includes('..')) {
      throw new Error(`Unsafe archive entry name: ${file.name}`);
    }
    const name = utf8.encode(file.name);
    const data = file.data instanceof Uint8Array ? file.data : utf8.encode(String(file.data));
    const compressed = deflateRawSync(Buffer.from(data), { level: 9 });
    const checksum = crc32(data);

    const local = Buffer.alloc(30);
    local.writeUInt32LE(0x04034b50, 0);
    local.writeUInt16LE(20, 4); // version needed
    local.writeUInt16LE(0, 6); // flags
    local.writeUInt16LE(8, 8); // deflate
    local.writeUInt16LE(0, 10); // time
    local.writeUInt16LE(0x21, 12); // 1980-01-01
    local.writeUInt32LE(checksum, 14);
    local.writeUInt32LE(compressed.length, 18);
    local.writeUInt32LE(data.length, 22);
    local.writeUInt16LE(name.length, 26);
    local.writeUInt16LE(0, 28);
    localParts.push(local, name, compressed);

    const central = Buffer.alloc(46);
    central.writeUInt32LE(0x02014b50, 0);
    central.writeUInt16LE(20, 4); // made by
    central.writeUInt16LE(20, 6); // needed
    central.writeUInt16LE(0, 8);
    central.writeUInt16LE(8, 10);
    central.writeUInt16LE(0, 12);
    central.writeUInt16LE(0x21, 14);
    central.writeUInt32LE(checksum, 16);
    central.writeUInt32LE(compressed.length, 20);
    central.writeUInt32LE(data.length, 24);
    central.writeUInt16LE(name.length, 28);
    central.writeUInt16LE(0, 30); // extra
    central.writeUInt16LE(0, 32); // comment
    central.writeUInt16LE(0, 34); // disk
    central.writeUInt16LE(0, 36); // internal attrs
    central.writeUInt32LE(0, 38); // external attrs
    central.writeUInt32LE(offset, 42);
    centralParts.push(central, name);

    offset += local.length + name.length + compressed.length;
  }

  const centralSize = centralParts.reduce((total, part) => total + part.length, 0);
  const end = Buffer.alloc(22);
  end.writeUInt32LE(0x06054b50, 0);
  end.writeUInt16LE(0, 4);
  end.writeUInt16LE(0, 6);
  end.writeUInt16LE(files.length, 8);
  end.writeUInt16LE(files.length, 10);
  end.writeUInt32LE(centralSize, 12);
  end.writeUInt32LE(offset, 16);
  end.writeUInt16LE(0, 20);
  return Buffer.concat([...localParts, ...centralParts, end]);
}

function collectFiles(directory, prefix = '') {
  const files = [];
  const entries = readdirSync(directory, { withFileTypes: true }).sort((a, b) =>
    a.name.localeCompare(b.name),
  );
  for (const entry of entries) {
    const full = join(directory, entry.name);
    const name = prefix ? `${prefix}/${entry.name}` : entry.name;
    if (entry.isDirectory()) {
      files.push(...collectFiles(full, name));
    } else if (entry.isFile()) {
      files.push({ name, data: readFileSync(full) });
    }
  }
  return files;
}

/** Package `distDir` into a store ZIP and an identical Firefox XPI. */
export function packageExtension({ distDir, outDir, name = 'newpgp-browser' } = {}) {
  if (!distDir || !existsSync(distDir)) throw new Error(`Build the extension first: ${distDir ?? '(no dist directory)'}`);
  const manifestPath = join(distDir, 'manifest.json');
  if (!existsSync(manifestPath)) throw new Error('dist/manifest.json is missing; run npm run build first.');

  const manifest = JSON.parse(readFileSync(manifestPath, 'utf8'));
  const version = manifest.version;
  if (typeof version !== 'string' || !/^\d+\.\d+\.\d+/.test(version)) {
    throw new Error(`Invalid manifest version: ${String(version)}`);
  }

  const files = collectFiles(distDir);
  if (!files.some((file) => file.name === 'manifest.json')) throw new Error('Archive would have no manifest.json.');

  const target = outDir ?? dirname(distDir);
  mkdirSync(target, { recursive: true });
  const archive = createZip(files);
  const zipPath = join(target, `${name}-${version}.zip`);
  const xpiPath = join(target, `${name}-${version}.xpi`);
  writeFileSync(zipPath, archive);
  writeFileSync(xpiPath, archive);

  return {
    zipPath,
    xpiPath,
    version,
    files: files.map((file) => file.name),
    bytes: archive.length,
  };
}

const invokedPath = process.argv[1] ? resolve(process.argv[1]) : '';
if (invokedPath && pathToFileURL(invokedPath).href === import.meta.url) {
  const root = dirname(dirname(fileURLToPath(import.meta.url)));
  const result = packageExtension({ distDir: join(root, 'dist'), outDir: root });
  console.log(`Packaged ${result.version}:`);
  console.log(`  ${basename(result.zipPath)} (${result.files.length} files, ${result.bytes} bytes)`);
  console.log(`  ${basename(result.xpiPath)} (Firefox self-distribution, unsigned)`);
}
