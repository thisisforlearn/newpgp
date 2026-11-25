// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Vaibhav - https://github.com/thisisforlearn
import { describe, expect, it } from 'vitest';
import {
  armorBody,
  decodeHtmlEntities,
  decodeQuotedPrintable,
  normalizeArmor,
  normalizeArmorBlocks,
  normalizeSmartQuotes,
  stripHtmlWrappers,
  stripQuoteMarkers,
} from '../src/crypto/normalizeArmor';

const CLEAN = [
  '-----BEGIN PGP MESSAGE-----',
  'Version: NewPGP 0.1',
  '',
  'QUJDRA==',
  '-----END PGP MESSAGE-----',
].join('\n');

describe('normalizeArmor', () => {
  it('keeps an already clean block unchanged', () => {
    const result = normalizeArmor(CLEAN);
    expect(result.found).toBe(true);
    expect(result.kind).toBe('PGP MESSAGE');
    expect(result.armored).toBe(CLEAN);
  });

  it('handles CRLF line endings', () => {
    const result = normalizeArmor(CLEAN.replace(/\n/g, '\r\n'));
    expect(result.found).toBe(true);
    expect(result.armored).toBe(CLEAN);
  });

  it('strips single and nested email quote markers', () => {
    const quoted = ['> -----BEGIN PGP MESSAGE-----', '> > QUJDRA==', '> -----END PGP MESSAGE-----'].join('\n');
    expect(normalizeArmor(quoted).armored).toBe(CLEAN.replace('Version: NewPGP 0.1\n', ''));
  });

  it('strips HTML wrappers and br tags', () => {
    const html =
      '<div>-----BEGIN PGP MESSAGE-----</div><br><span>QUJDRA==</span><br><p>-----END PGP MESSAGE-----</p>';
    expect(stripHtmlWrappers(html)).toContain('-----BEGIN PGP MESSAGE-----');
    expect(normalizeArmor(html).armored).toBe(CLEAN.replace('Version: NewPGP 0.1\n', ''));
  });

  it('decodes HTML entities used for quote markers', () => {
    expect(decodeHtmlEntities('&gt; &lt; &amp; &quot; &#45;')).toBe('> < & " -');
    const html = '&gt; -----BEGIN PGP MESSAGE-----<br>QUJDRA==<br>&gt; -----END PGP MESSAGE-----';
    expect(normalizeArmor(html).found).toBe(true);
  });

  it('decodes quoted-printable =3D padding and soft breaks', () => {
    expect(decodeQuotedPrintable('QUJD=3D=3D')).toBe('QUJD==');
    expect(decodeQuotedPrintable('QUJD=\nRA==')).toBe('QUJDRA==');
    const mangled = ['-----BEGIN PGP MESSAGE-----', '', 'QUJDRA=3D=3D', '-----END PGP MESSAGE-----'].join('\n');
    expect(normalizeArmor(mangled).armored).toBe(CLEAN.replace('Version: NewPGP 0.1\n', ''));
  });

  it('normalizes smart quotes, dashes and zero-width characters', () => {
    expect(normalizeSmartQuotes('“x” ‘y’ — \u00a0\u200b')).toBe('"x" \'y\' -  ');
    const mangled = '\u200b“-----BEGIN PGP MESSAGE-----”\nQUJDRA==\n“-----END PGP MESSAGE-----”';
    expect(normalizeArmor(mangled).found).toBe(true);
  });

  it('joins stray line breaks inside the base64 body and rewraps it', () => {
    const longBody = 'QUJDRA==QUJDRA==QUJDRA==QUJDRA==QUJDRA==QUJDRA==QUJDRA==QUJDRA==QUJDRA==';
    const mangled = [
      '-----BEGIN PGP MESSAGE-----',
      '',
      longBody.slice(0, 20),
      longBody.slice(20, 41),
      longBody.slice(41),
      '-----END PGP MESSAGE-----',
    ].join('\n');
    const result = normalizeArmor(mangled);
    expect(result.found).toBe(true);
    const lines = result.armored.split('\n');
    expect(lines[2]?.length).toBeLessThanOrEqual(64);
    expect(armorBody(result.armored)).toBe(longBody);
  });

  it('removes prose around the block', () => {
    const text = [
      'Hey, here is the encrypted copy:',
      '',
      '-----BEGIN PGP MESSAGE-----',
      '',
      'QUJDRA==',
      '-----END PGP MESSAGE-----',
      '',
      'Thanks!',
    ].join('\n');
    const result = normalizeArmor(text);
    expect(result.armored).toBe(CLEAN.replace('Version: NewPGP 0.1\n', ''));
    expect(result.armored).not.toContain('Thanks');
  });

  it('preserves armor headers such as Version and Comment', () => {
    const text = [
      '-----BEGIN PGP MESSAGE-----',
      'Version: Test 1',
      'Comment: hello',
      '',
      'QUJDRA==',
      '-----END PGP MESSAGE-----',
    ].join('\n');
    expect(normalizeArmor(text).armored).toBe(text);
  });

  it('trims trailing whitespace from every line', () => {
    const text = '-----BEGIN PGP MESSAGE-----   \n   \nQUJDRA==   \n-----END PGP MESSAGE-----  ';
    expect(normalizeArmor(text).armored).toBe(CLEAN.replace('Version: NewPGP 0.1\n', ''));
  });

  it('finds several blocks and normalizes each one', () => {
    const first = ['-----BEGIN PGP MESSAGE-----', '', 'QUJDRA==', '-----END PGP MESSAGE-----'].join('\n');
    const second = ['-----BEGIN PGP MESSAGE-----', '', 'RUVGRw==', '-----END PGP MESSAGE-----'].join('\n');
    const blocks = normalizeArmorBlocks(`${first}\n---\n${second}`);
    expect(blocks).toHaveLength(2);
    expect(armorBody(blocks[0]?.armored ?? '')).toBe('QUJDRA==');
    expect(armorBody(blocks[1]?.armored ?? '')).toBe('RUVGRw==');
  });

  it('reports no block instead of inventing one', () => {
    const result = normalizeArmor('just a normal email');
    expect(result.found).toBe(false);
    expect(result.armored).toBe('');
    expect(result.kind).toBeNull();
  });

  it('warns about stray characters in the body', () => {
    const text = '-----BEGIN PGP MESSAGE-----\n\nQUJD!!RA==\n-----END PGP MESSAGE-----';
    const result = normalizeArmor(text);
    expect(result.found).toBe(true);
    expect(result.warnings.some((warning) => warning.includes('stray character'))).toBe(true);
    expect(armorBody(result.armored)).toBe('QUJDRA==');
  });

  it('leaves an unfinished block unfound', () => {
    const result = normalizeArmor('-----BEGIN PGP MESSAGE-----\n\nQUJDRA==');
    expect(result.found).toBe(false);
    expect(result.warnings.some((warning) => warning.includes('unfinished'))).toBe(true);
  });

  it('supports signature and public-key armor kinds', () => {
    const sig = '-----BEGIN PGP SIGNATURE-----\n\nQUJDRA==\n-----END PGP SIGNATURE-----';
    expect(normalizeArmor(sig).kind).toBe('PGP SIGNATURE');
    const key = '-----BEGIN PGP PUBLIC KEY BLOCK-----\n\nQUJDRA==\n-----END PGP PUBLIC KEY BLOCK-----';
    expect(normalizeArmor(key).kind).toBe('PGP PUBLIC KEY BLOCK');
  });
});
