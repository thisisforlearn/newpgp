// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Vaibhav - https://github.com/thisisforlearn
/**
 * Pure armor cleanup. Webmail clients mangle armored blocks in predictable
 * ways: HTML wrappers, `>` quote markers, quoted-printable `=3D`, smart
 * quotes, trailing spaces and stray line breaks. This module turns all of
 * those back into a canonical, parseable armored block without touching the
 * base64 payload.
 *
 * Nothing here mutates its input and nothing here talks to the network or to
 * storage, which makes the whole pipeline trivially unit-testable.
 */

export type ArmorKind = string;

export interface NormalizeResult {
  found: boolean;
  /** Canonical armor, or an empty string when no complete block was found. */
  armored: string;
  kind: ArmorKind | null;
  warnings: string[];
}

interface RawArmorBlock {
  kind: ArmorKind;
  armorHeaders: string[];
  body: string;
}

const BASE64_CHARS = /^[A-Za-z0-9+/=]*$/;
const BEGIN_LINE = /^-----BEGIN (PGP [A-Z0-9 ]+)-----$/;
const ARMOR_HEADER_LINE = /^[A-Za-z][A-Za-z0-9-]*: .+$/;
/** RFC 4880 armor checksum: `=` followed by four base64 characters, on its own line. */
const CRC_LINE = /^=[A-Za-z0-9+/]{4}$/;

/** Remove HTML wrappers while keeping a newline where a block boundary was. */
export function stripHtmlWrappers(input: string): string {
  return input
    .replace(/<script\b[^>]*>[\s\S]*?<\/script>/gi, '\n')
    .replace(/<style\b[^>]*>[\s\S]*?<\/style>/gi, '\n')
    .replace(/<br\s*\/?>/gi, '\n')
    .replace(/<\/(?:p|div|li|tr|blockquote|pre|h[1-6])>/gi, '\n')
    .replace(/<[^>]*>/g, '');
}

/** Decode the handful of entities that webmail editors insert around armor. */
export function decodeHtmlEntities(input: string): string {
  const named: Record<string, string> = {
    amp: '&',
    lt: '<',
    gt: '>',
    quot: '"',
    apos: "'",
    nbsp: ' ',
    mdash: '-',
    ndash: '-',
  };
  return input
    .replace(/&(#x[0-9a-f]+|#\d+|[a-zA-Z]+);/gi, (whole, entity: string) => {
      if (entity.startsWith('#x')) {
        const code = Number.parseInt(entity.slice(2), 16);
        return Number.isFinite(code) && code <= 0x10ffff ? String.fromCodePoint(code) : whole;
      }
      if (entity.startsWith('#')) {
        const code = Number.parseInt(entity.slice(1), 10);
        return Number.isFinite(code) && code <= 0x10ffff ? String.fromCodePoint(code) : whole;
      }
      return named[entity.toLowerCase()] ?? whole;
    });
}

/** Curly quotes and exotic spaces are cosmetic noise around armor markers. */
export function normalizeSmartQuotes(input: string): string {
  return input
    .replace(/[\u201c\u201d\u201e\u201f\u00ab\u00bb]/g, '"')
    .replace(/[\u2018\u2019\u201b\u2039\u203a]/g, "'")
    .replace(/[\u2013\u2014\u2212]/g, '-')
    .replace(/[\u00a0\u2007\u202f]/g, ' ')
    .replace(/[\u200b\u200c\u200d\ufeff]/g, '');
}

/** Remove email-client quote prefixes such as `>` and `> > `. */
export function stripQuoteMarkers(input: string): string {
  return input
    .split('\n')
    .map((line) => line.replace(/^[\s]*(?:>[ \t]?)+/, ''))
    .join('\n');
}

/**
 * Undo quoted-printable encoding. `=3D` becomes `=`, `=20` becomes a space,
 * and a trailing `=` continues the line (soft break) instead of ending it.
 */
export function decodeQuotedPrintable(input: string): string {
  const lines = input.split('\n');
  const output: string[] = [];
  let carry = '';

  for (let index = 0; index < lines.length; index += 1) {
    let current = carry + (lines[index] ?? '');
    carry = '';
    // An armor CRC line starts with '=' but is NOT quoted-printable. Leave it
    // intact so normalizeArmorBlocks can drop it before joining the base64.
    if (CRC_LINE.test(current)) {
      output.push(current);
      continue;
    }
    if (/=$/.test(current) && isSoftBreak(current, lines[index + 1] ?? '')) {
      carry = current.slice(0, -1);
      continue;
    }
    output.push(
      current.replace(/=([0-9a-f]{2})/gi, (whole, hex: string) => {
        const code = Number.parseInt(hex, 16);
        if (code === 0x3d) return '=';
        if (code === 0x20) return ' ';
        if (code === 0x09) return '\t';
        if (code === 0x0a || code === 0x0d) return '';
        return String.fromCharCode(code);
      }),
    );
  }
  if (carry.length > 0) output.push(carry);
  return output.join('\n');
}

/**
 * A trailing `=` is ambiguous: it can be a quoted-printable soft break OR
 * base64 padding. Only treat it as a soft break when the following line
 * actually looks like a continuation of the base64 body — never when the next
 * line is an armor footer or an armor header.
 */
function isSoftBreak(line: string, nextLine: string): boolean {
  if (line.endsWith('==')) return false;
  const next = nextLine.trim();
  if (next.length === 0) return false;
  if (next.startsWith('-----')) return false;
  if (/^[A-Za-z][A-Za-z0-9-]*: /.test(next)) return false;
  return /^[A-Za-z0-9+/=]+$/.test(next);
}

function cleanLine(line: string): string {
  let value = line.trim();
  if (
    value.length >= 2 &&
    ((value.startsWith('"') && value.endsWith('"')) || (value.startsWith("'") && value.endsWith("'")))
  ) {
    value = value.slice(1, -1).trim();
  }
  return value;
}

/** Wrap a base64 body at the conventional 64 characters per line. */
export function wrapBase64(body: string, width = 64): string[] {
  if (body.length === 0) return [];
  const chunks: string[] = [];
  for (let i = 0; i < body.length; i += width) {
    chunks.push(body.slice(i, i + width));
  }
  return chunks;
}

function prepareText(text: string): string[] {
  const withMarkersOnOwnLines = normalizeSmartQuotes(
    decodeHtmlEntities(stripHtmlWrappers(text.replace(/\r\n?/g, '\n'))),
  )
    .replace(/(-----BEGIN PGP [A-Z0-9 ]+-----)/g, '\n$1\n')
    .replace(/(-----END PGP [A-Z0-9 ]+-----)/g, '\n$1\n');

  return decodeQuotedPrintable(stripQuoteMarkers(withMarkersOnOwnLines))
    .split('\n')
    .map(cleanLine);
}

/** Find and canonicalize every complete armored block in a piece of text. */
export function normalizeArmorBlocks(raw: string): NormalizeResult[] {
  if (typeof raw !== 'string' || raw.length === 0) return [];

  const lines = prepareText(raw);
  const blocks: NormalizeResult[] = [];
  const warnings: string[] = [];

  for (let i = 0; i < lines.length; i += 1) {
    const begin = BEGIN_LINE.exec(lines[i] ?? '');
    if (!begin) continue;
    const kind = begin[1] ?? 'PGP MESSAGE';
    const footer = `-----END ${kind}-----`;

    let endIndex = -1;
    for (let j = i + 1; j < lines.length; j += 1) {
      if (lines[j] === footer) {
        endIndex = j;
        break;
      }
    }
    if (endIndex === -1) {
      warnings.push(`An unfinished “${kind}” block was left untouched.`);
      if (blocks.length === 0) {
        blocks.push({ found: false, armored: '', kind, warnings: [...warnings] });
      }
      warnings.length = 0;
      continue;
    }

    const inner = lines.slice(i + 1, endIndex);
    const armorHeaders: string[] = [];
    let cursor = 0;
    while (cursor < inner.length && (inner[cursor] ?? '') === '') cursor += 1;
    while (cursor < inner.length) {
      const line = inner[cursor] ?? '';
      if (line === '') break;
      if (!ARMOR_HEADER_LINE.test(line)) break;
      armorHeaders.push(line);
      cursor += 1;
    }

    const payloadLines = inner.slice(cursor).filter((line) => line !== '');
    // Canonical armor ends with a CRC24 checksum line. It is not base64 data:
    // appending it to the ciphertext corrupts the payload (e.g. `...dA==AbCd`).
    if (payloadLines.length > 0 && CRC_LINE.test(payloadLines[payloadLines.length - 1] ?? '')) {
      payloadLines.pop();
    }
    const collected = payloadLines.join('').replace(/\s+/g, '');
    const unexpected = collected.replace(/[A-Za-z0-9+/=]/g, '');
    if (unexpected.length > 0) {
      warnings.push(`Removed ${unexpected.length} stray character${unexpected.length === 1 ? '' : 's'} from the block body.`);
    }
    const body = collected.replace(/[^A-Za-z0-9+/=]/g, '');
    if (!BASE64_CHARS.test(body)) {
      warnings.push('The block body contains characters that are not valid base64.');
    }
    if (body.length === 0) {
      warnings.push(`The “${kind}” block had no body and was skipped.`);
      i = endIndex;
      continue;
    }

    const canonical = [
      `-----BEGIN ${kind}-----`,
      ...armorHeaders,
      '',
      ...wrapBase64(body),
      footer,
    ].join('\n');

    blocks.push({ found: true, armored: canonical, kind, warnings: [...warnings] });
    warnings.length = 0;
    i = endIndex;
  }

  return blocks;
}

/**
 * Normalize the first armored block in a message. This is the function the
 * decrypt and send paths use; use `normalizeArmorBlocks` for a whole mailbox.
 */
export function normalizeArmor(raw: string): NormalizeResult {
  const blocks = normalizeArmorBlocks(raw);
  const first = blocks.find((block) => block.found);
  if (first) return first;
  return blocks[0] ?? { found: false, armored: '', kind: null, warnings: [] };
}

/** Extract just the base64 payload from an already canonical armored block. */
export function armorBody(armored: string): string {
  const lines = armored.split('\n').map((line) => line.trim());
  const begin = lines.findIndex((line) => BEGIN_LINE.test(line));
  if (begin === -1) return '';
  const kind = BEGIN_LINE.exec(lines[begin] ?? '')?.[1] ?? 'PGP MESSAGE';
  const footer = `-----END ${kind}-----`;
  const end = lines.indexOf(footer, begin + 1);
  if (end === -1) return '';
  return lines
    .slice(begin + 1, end)
    .filter((line) => line !== '' && !ARMOR_HEADER_LINE.test(line) && !CRC_LINE.test(line))
    .join('')
    .replace(/\s+/g, '');
}
