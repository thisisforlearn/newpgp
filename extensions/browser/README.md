# NewPGP browser extension (Manifest V3)

A dependency-light TypeScript extension for **Chrome/Edge** and **Firefox** that integrates with Gmail and Outlook on the web.

> **Status:** early implementation. The webmail compose integration uses best-effort selectors and may need updates when Gmail or Outlook change their markup. See [Known limitations](#known-limitations).

## What it does

- **Send both (default).** Your typed message is kept exactly as written and an OpenPGP armored block is appended at the bottom. Non-PGP recipients can still read the email. A clearly labelled toggle switches to **PGP only**, which sends *only* the encrypted block — non-PGP recipients then cannot read it.
- **Formatting cleanup.** A pure `normalizeArmor()` function repairs quoted-printable `=3D`, HTML wrappers, `>` quote markers, smart quotes, trailing whitespace and stray line breaks before handing a block to the crypto backend (unit-tested).
- **Key discovery.** Paste an email address in the popup. The extension asks the native helper to look for a key via WKD / keys.openpgp.org / Autocrypt and shows the fingerprint, a short safety number, a plain-language explanation and a "verify out of band" hint. All failures are translated into friendly messages.
- **Decrypt in place.** Incoming `-----BEGIN PGP MESSAGE-----` blocks get a **Decrypt with NewPGP** button with a signature status: *Verified*, *Unverified — verify by comparing the safety number*, or *Invalid*.
- **Draft safety.** Composition text stays in memory in the content script. Only settings are written to `chrome.storage`; no plaintext, key material or decrypted text is ever stored by the extension.

## Native host

The extension does **not** contain cryptography. Heavy OpenPGP work and private keys live in the **NewPGP Rust CLI/daemon** installed on the user's computer. It registers a Chrome/Firefox native messaging host named:

```
com.newpgp.bridge
```

The protocol is typed JSON with a 4-byte little-endian length prefix (see `src/platform/framing.ts`). Requests are `generateKey`, `encrypt`, `decrypt`, `sign`, `verify`, `getKey` and `discoverKey`; each carries a request `id` and the response echoes it. The host implementation lives in the Rust workspace, not in this folder. `src/native/MockBridge.ts` is a deterministic in-memory stand-in for tests and UI development — it performs **no** real cryptography.

## Development

```bash
npm install
npm test          # vitest: normalization, composition, framing, errors, decrypt, client
npm run typecheck # tsc --noEmit
npm run lint      # static check via tsc (eslint intentionally omitted)
npm run build     # typecheck + esbuild bundles -> dist/
```

`npm run build` produces a loadable, unpacked extension in `dist/`:

- `dist/manifest.json`
- `dist/background/service-worker.js` (MV3 service worker)
- `dist/content/gmail.js`, `dist/content/outlook.js`
- `dist/ui/popup.js`, `dist/ui/options.js`, `dist/ui/styles.css`, `dist/ui/content.css`
- `dist/popup.html`, `dist/options.html`, `dist/icon.svg`

The build bundles each entry point as a classic IIFE so no ESM import resolution is needed at runtime. `esbuild` is the only build dependency; tests use `vitest` + `jsdom`.

## Install (Chrome / Edge)

1. Run `npm install && npm run build`.
2. Open `chrome://extensions` (or `edge://extensions`) and enable **Developer mode**.
3. Choose **Load unpacked** and select `extensions/browser/dist`.
4. Install and start the NewPGP desktop app/CLI so `com.newpgp.bridge` is registered.
5. Open the popup; it should say **Helper connected**.

## Install (Firefox)

1. Build as above.
2. Open `about:debugging#/runtime/this-firefox` → **Load Temporary Add-on** → select `dist/manifest.json`.
3. Firefox requires the native host manifest in `~/.mozilla/native-messaging-hosts/com.newpgp.bridge.json` (via the NewPGP desktop installer).
4. Temporary add-ons are removed when Firefox closes; use a signed build for permanent installation.

For a permanent, signed install you will need to package `dist/` and submit it to Mozilla with the `browser_specific_settings.gecko.id` already declared in `manifest.json`.

## Known limitations

- **Webmail selectors are heuristic.** The content script looks for common Gmail/Outlook compose and send-button attributes. Providers change these frequently; the interceptor may fail silently (it never sends unencrypted text when it cannot encrypt, except when NewPGP is explicitly disabled).
- **Plaintext is reinserted as text.** Restoring the composed body uses `textContent`, so rich-text formatting (bold, links) in the original draft is not preserved.
- **Send re-trigger is simulated.** After composing, the extension re-dispatches the send click/keyboard event with a one-shot bypass. Some provider UIs may require a manual second click if their internal state does not observe the `input` event.
- **Provider autosave.** Gmail and Outlook may autosave a draft *before* NewPGP encrypts it. The options page warns about this and recommends the built-in NewPGP client for high-sensitivity mail.
- **Icon placeholder.** `icon.svg` is a simple placeholder. Chrome may require PNG icons for some surfaces; convert to PNG if a warning appears. Firefox accepts SVG.
- **MockBridge is not crypto.** All tests run against the mock. Real encryption/decryption requires the native Rust host.
- **Signature status comes from the host.** "Invalid" and "Verified" reflect what `com.newpgp.bridge` reports; the browser extension does not independently verify signatures.

## File map

```
src/types.ts                       typed bridge + runtime message contracts
src/platform/browser.ts            minimal chrome/browser API shim
src/platform/framing.ts            4-byte LE native messaging framing + decoder
src/platform/utf8.ts               UTF-8 helpers
src/crypto/normalizeArmor.ts       pure armor cleanup (the formatting fix)
src/crypto/base64.ts, safetyNumber.ts
src/composition/compose.ts         send-both / PGP-only composition logic
src/composition/settings.ts        storage (settings only)
src/native/NativeMessagingClient.ts real host client + transports
src/native/MockBridge.ts           test/demo bridge
src/native/errors.ts               friendly error mapping
src/background/service-worker.ts   MV3 message router
src/content/common.ts              compose interception
src/content/incoming.ts            incoming block scanner + decrypt buttons
src/content/gmail.ts, outlook.ts   provider entry points
src/mail/decryptView.ts            signature copy + safe DOM rendering
src/ui/*                           popup/options UI, HTML, CSS
test/*                             vitest suites + mock browser
```
