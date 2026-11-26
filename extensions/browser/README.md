# NewPGP browser extension (Manifest V3)

A dependency-light TypeScript extension for **Chrome, Edge and Firefox** that adds OpenPGP to Gmail and Outlook on the web.

> **Status:** early implementation. The webmail compose integration uses best-effort selectors and may need updates when Gmail or Outlook change their markup. See [Known limitations](#known-limitations).

Official project: https://github.com/thisisforlearn/newpgp — licensed **GPL-3.0-or-later**.
Author: Vaibhav ([@thisisforlearn](https://github.com/thisisforlearn)). Contributor: [@sudo-linux-neko](https://github.com/sudo-linux-neko).
Privacy questions: nekomeowpurr@proton.me

## What it does

- **Send both (default).** Your typed message is kept exactly as written and an OpenPGP armored block is appended at the bottom. Non-PGP recipients can still read the email. A clearly labelled toggle switches to **PGP only**, which sends *only* the encrypted block — non-PGP recipients then cannot read it.
- **Fail closed.** If encryption does not produce a complete PGP block, the send is blocked with a friendly notice. The extension never silently falls back to plaintext, in either mode.
- **Formatting cleanup.** A pure `normalizeArmor()` function repairs quoted-printable `=3D`, HTML wrappers, `>` quote markers, smart quotes, trailing whitespace, stray line breaks and trailing CRC24 checksum lines before handing a block to the crypto backend.
- **Key discovery.** Paste an email address in the popup. The extension asks the local helper to look for a key via WKD / keys.openpgp.org / Autocrypt and shows the fingerprint, a short safety number, a plain-language explanation and a "verify out of band" hint. Failures are translated into friendly messages.
- **Decrypt in place.** Incoming `-----BEGIN PGP MESSAGE-----` blocks get a **Decrypt with NewPGP** button with a signature status: *Verified*, *Unverified — verify by comparing the safety number*, or *Invalid*.
- **Draft safety.** Composition text stays in memory in the content script. Only three settings are written to `chrome.storage`; no plaintext, key material or decrypted text is ever stored by the extension. Rich text in the draft is preserved. See [PRIVACY.md](PRIVACY.md).

## Requirements

1. A Chromium browser (Chrome/Edge) or **Firefox 142+**.
2. The `newpgp` CLI built or downloaded from the repository. It runs in **bridge mode** and owns your keys and all OpenPGP work.
3. A one-time manual registration of the native messaging host `com.newpgp.bridge`. The browser starts the helper **on demand** for each connection; there is no daemon to keep running.

## Install (Chrome / Edge)

1. `npm install && npm run build`.
2. Open `chrome://extensions` (or `edge://extensions`) and enable **Developer mode**.
3. Choose **Load unpacked** and select `extensions/browser/dist`.
4. Copy the extension ID shown on that page (32 lowercase letters `a`–`p`).
5. Register the helper:
   - **Windows (PowerShell):** `.\native-host\install.ps1 -ChromeExtensionId <your-id> -Executable "C:\path\to\newpgp.exe"`
   - **macOS/Linux:** `./native-host/install.sh <your-id> [firefox-id] [path-to-newpgp]`
6. Restart the browser and reload the extension. The popup should say **Helper connected**.

The ID in `com.newpgp.bridge.json` must match exactly, or the browser refuses to launch the helper. See [`native-host/README.md`](../../native-host/README.md) for registry details and the JSON template.

## Install (Firefox)

1. Build as above. **Firefox 142 or newer is required** (the manifest declares `data_collection_permissions` and a Gecko extension ID).
2. Open `about:debugging#/runtime/this-firefox` → **Load Temporary Add-on** → select `dist/manifest.json`.
3. This add-on's ID is `newpgp@newpgp.local`. Register the helper with `./native-host/install.sh <chrome-id> newpgp@newpgp.local [path-to-newpgp]` (on Windows add `-FirefoxExtensionId newpgp@newpgp.local` to the PowerShell command). The browser will place the manifest in its `native-messaging-hosts` folder.
4. Restart Firefox and reload the add-on. Temporary add-ons are removed when Firefox closes.

For a permanent install, the XPI must be **signed by AMO**. Upload a submitted build with the declared `browser_specific_settings.gecko.id`; `web-ext sign` can do this with valid AMO credentials. An unsigned XPI from `npm run package` is the self-distribution equivalent and only installs in builds that allow unsigned add-ons. **Firefox for Android is not supported**: it has no native messaging, so the helper cannot be registered there, and no `gecko_android` support is advertised.

## Development

```bash
npm install
npm test          # vitest: 94 tests across composition, framing, armor, UI, packaging
npm run typecheck # tsc --noEmit
npm run lint      # static check via tsc (eslint intentionally omitted)
npm run build     # typecheck + esbuild bundles + real PNG icons -> dist/
npx web-ext lint --source-dir dist   # Mozilla linter
npm run package   # ZIP + XPI from dist/
```

`npm run build` produces a loadable, unpacked extension in `dist/`:

- `dist/manifest.json`
- `dist/background/service-worker.js` (MV3 service worker; also the Firefox fallback background script)
- `dist/content/gmail.js`, `dist/content/outlook.js`
- `dist/ui/popup.js`, `dist/ui/options.js`, `dist/ui/styles.css`, `dist/ui/content.css`
- `dist/popup.html`, `dist/options.html`
- `dist/icon.svg` (in-page UI) and `icon16.png`, `icon32.png`, `icon48.png`, `icon128.png` (manifest icons — Chrome does not accept SVG there)

Each entry point is bundled as a classic IIFE so no ESM import resolution is needed at runtime. `esbuild` is the only build dependency; tests use `vitest` + `jsdom`. `web-ext` is a dev dependency used for Mozilla linting and optional signing.

### Build a store package from source

```bash
npm ci
npm run build
npm run package
```

This writes `newpgp-browser-<version>.zip` (Chrome Web Store / Edge) and `newpgp-browser-<version>.xpi` (Firefox self-distribution; an XPI is just a ZIP with `manifest.json` at the root). Both contain only `dist/` files. The script is pure Node (`scripts/package.mjs`) and works on Windows, macOS and Linux; `dist/` and the generated archives are gitignored.

To submit to Mozilla, sign the XPI with `npx web-ext sign --source-dir dist --api-key <key> --api-secret <secret>` using real AMO credentials. Publishing to any store needs credentials and reviewer approval; see [STORE_LISTING.md](STORE_LISTING.md) for the copy and reviewer instructions.

## How it works

The extension does **not** contain cryptography. Heavy OpenPGP work and private keys live in the local `newpgp` CLI. It registers the native messaging host named:

```
com.newpgp.bridge
```

The protocol is typed JSON with a 4-byte little-endian length prefix (see `src/platform/framing.ts`). Requests are `generateKey`, `encrypt`, `decrypt`, `sign`, `verify`, `getKey` and `discoverKey`; each carries a request `id` and the response echoes it. `src/native/MockBridge.ts` is a deterministic in-memory stand-in for tests and UI development — it performs **no** real cryptography.

The service worker answers only its own extension's messages, and the content script resolves the compose editor from the **clicked** send button so two open compose windows cannot be crossed.

## Known limitations

- **Webmail selectors are heuristic.** The content script looks for common Gmail/Outlook compose and send-button attributes. Providers change these frequently; the interceptor may fail to recognise a compose window. It never sends unencrypted text when it cannot encrypt.
- **Plaintext is reinserted as text plus a `<pre>` block.** In send-both mode the original rich text (bold, links) is preserved and the armor is appended as a separate `<pre>` element; some providers may still flatten formatting when sending.
- **Send re-trigger is simulated.** After composing, the extension re-dispatches a marked click and lets the provider's own handler run. Some provider UIs may require a manual second click if their internal state does not observe the `input` event.
- **Provider autosave.** Gmail and Outlook may autosave a draft *before* NewPGP encrypts it. The options page warns about this and recommends the built-in NewPGP client for high-sensitivity mail.
- **Firefox background fallback.** `web-ext lint` reports `BACKGROUND_SERVICE_WORKER_IGNORED`: Firefox ignores `background.service_worker` and runs `background.scripts` instead. Both point to the same file and the warning is expected for a cross-browser MV3 manifest.
- **Signature status comes from the host.** "Invalid" and "Verified" reflect what `com.newpgp.bridge` reports; the browser extension does not independently verify signatures.
- **Manual host registration.** There is no installer bundled with the extension itself; users must run the scripts in `native-host/` once. An ID mismatch is the most common "Helper not found" cause.

## File map

```
src/types.ts                       typed bridge + runtime message contracts
src/messages.ts                    background call helper + friendly results
src/platform/browser.ts            minimal chrome/browser API shim
src/platform/framing.ts            4-byte LE native messaging framing + recovery
src/platform/utf8.ts               UTF-8 helpers (fatal option for frames)
src/crypto/normalizeArmor.ts       pure armor cleanup incl. CRC24 handling
src/crypto/base64.ts, safetyNumber.ts
src/composition/compose.ts         send-both / PGP-only composition, fail-closed
src/composition/settings.ts        settings-only storage with in-memory cache
src/native/NativeMessagingClient.ts real host client + transports
src/native/MockBridge.ts           test/demo bridge
src/native/errors.ts               friendly error mapping
src/background/service-worker.ts   MV3 router + sender validation
src/content/common.ts              button-scoped compose interception
src/content/incoming.ts            incoming block scanner + decrypt buttons
src/content/gmail.ts, outlook.ts   provider entry points
src/mail/decryptView.ts            signature copy + safe DOM rendering
src/ui/*                           popup/options UI, HTML, CSS
scripts/build.mjs                  esbuild + asset copies + PNG icons
scripts/icons.mjs                  dependency-free PNG icon rasteriser
scripts/package.mjs                cross-platform ZIP/XPI packaging
test/*                             vitest suites + mock browser
STORE_LISTING.md                   store copy, keywords, screenshots, reviewer notes
PRIVACY.md                         plain-language privacy policy
```
