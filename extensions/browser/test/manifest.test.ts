// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Vaibhav - https://github.com/thisisforlearn
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { describe, expect, it } from 'vitest';

const manifest = JSON.parse(readFileSync(join(process.cwd(), 'manifest.json'), 'utf8')) as Record<string, any>;
const WEBMAIL_ORIGINS = [
  'https://mail.google.com/*',
  'https://outlook.office.com/*',
  'https://outlook.office365.com/*',
  'https://outlook.live.com/*',
];

describe('release manifest shape', () => {
  it('is MV3 with a Firefox-compatible background fallback', () => {
    expect(manifest.manifest_version).toBe(3);
    expect(manifest.background.service_worker).toBe('background/service-worker.js');
    expect(manifest.background.scripts).toEqual(['background/service-worker.js']);
  });

  it('requests only the permissions the extension actually needs', () => {
    expect([...manifest.permissions].sort()).toEqual(['nativeMessaging', 'storage']);
    expect(JSON.stringify(manifest)).not.toContain('<all_urls>');
    expect(manifest.host_permissions).toEqual(WEBMAIL_ORIGINS);
  });

  it('never grants web-accessible resources beyond the two webmail hosts', () => {
    for (const resource of manifest.web_accessible_resources) {
      expect(resource.matches.every((match: string) => WEBMAIL_ORIGINS.includes(match))).toBe(true);
      expect(resource.resources.every((path: string) => !path.includes('..'))).toBe(true);
    }
  });

  it('scopes content scripts to the declared host permissions', () => {
    const matches = manifest.content_scripts.flatMap((script: any) => script.matches);
    expect(matches.length).toBeGreaterThan(0);
    for (const match of matches) expect(WEBMAIL_ORIGINS).toContain(match);
  });

  it('declares a signed-friendly Gecko id, minimum version and no data collection', () => {
    const gecko = manifest.browser_specific_settings.gecko;
    expect(gecko.id).toMatch(/^[@a-zA-Z0-9._-]+@[a-zA-Z0-9._-]+$/);
    expect(gecko.strict_min_version).toBe('142.0');
    expect(gecko.data_collection_permissions).toEqual({ required: ['none'] });
    // Native messaging is not available on Firefox for Android; we do not
    // advertise gecko_android support.
    expect(manifest.browser_specific_settings).not.toHaveProperty('gecko_android');
  });

  it('uses a strict extension-pages CSP with no inline or remote script', () => {
    const csp: string = manifest.content_security_policy.extension_pages;
    expect(csp).toContain("script-src 'self'");
    expect(csp).toContain("object-src 'self'");
    expect(csp).not.toMatch(/unsafe-eval|unsafe-inline|https?:|wasm-unsafe-eval/);
  });

  it('declares real raster icons, including the required 128px', () => {
    const icons = manifest.icons;
    expect(Object.keys(icons).sort()).toEqual(['128', '16', '32', '48']);
    for (const path of Object.values(icons)) expect(path).toMatch(/\.png$/);
    expect(manifest.action.default_icon['128']).toMatch(/\.png$/);
  });

  it('does not declare the native host itself (the local installer registers it)', () => {
    expect(JSON.stringify(manifest)).not.toContain('com.newpgp.bridge');
    expect(manifest.permissions).toContain('nativeMessaging');
  });
});

describe('release-facing copy', () => {
  it('describes the real manual native-host install, not a daemon', () => {
    const html = readFileSync(join(process.cwd(), 'src', 'ui', 'options.html'), 'utf8');
    expect(html).toContain('install.ps1');
    expect(html).toContain('install.sh');
    expect(html).toContain('com.newpgp.bridge.json');
    expect(html).toContain('starts the helper automatically');
    expect(html.toLowerCase()).not.toMatch(/cli daemon|make sure it is running|desktop app or/i);
  });
});
