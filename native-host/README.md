# Native messaging host

The browser extension needs a local helper to do real OpenPGP work. That helper
is the `newpgp` CLI binary (`crates/newpgp-cli`) running in **bridge mode**:

```text
newpgp bridge
```

It reads Chrome's 4-byte little-endian framed JSON from stdin and writes framed
JSON to stdout. Diagnostics go to stderr. No plaintext is persisted by the
bridge; keys and the contact list live in your OS user data folder.

## Install

1. Build or download `newpgp` (`newpgp.exe` on Windows).
2. Load the unpacked extension from `extensions/browser/dist` and note its ID.
   Chrome/Edge IDs are 32 lowercase letters `a`–`p`; Firefox add-on IDs usually
   look like `newpgp@example.org`.
3. Run the installer with that ID:
   - **Windows (PowerShell):** `.\install.ps1 -ChromeExtensionId <id> -FirefoxExtensionId <id> -Executable "C:\path\to\newpgp.exe"`. Either ID may be omitted, but at least one is required.
   - **macOS/Linux:** `./install.sh <chrome-or-edge-id> [firefox-id] [path-to-newpgp]`. A Chrome/Edge ID is required; the Firefox ID is optional.
4. Restart the browser and reload the extension.

### What gets registered

- A single manifest at `%LOCALAPPDATA%\NewPGP\com.newpgp.bridge.json` on Windows
  (or the browser's own `NativeMessagingHosts` folder on macOS/Linux). The
  executable path is JSON-escaped, and only the IDs you supplied are written.
- **On Windows, browsers find hosts through the registry, not by scanning
  folders.** The installer creates these `HKCU` keys and sets each key's
  **(Default)** value to the absolute manifest path:
  - `HKCU:\Software\Google\Chrome\NativeMessagingHosts\com.newpgp.bridge`
  - `HKCU:\Software\Microsoft\Edge\NativeMessagingHosts\com.newpgp.bridge`
  - `HKCU:\Software\Mozilla\NativeMessagingHosts\com.newpgp.bridge`

  A key is created only for the browsers whose ID you supplied. Chrome and Edge
  share the `chrome-extension://` origin format, so a Chrome ID registers both;
  Firefox needs its own add-on ID. Authoritative references:
  [Chrome native messaging](https://developer.chrome.com/docs/extensions/develop/concepts/native-messaging)
  and [MDN native messaging](https://developer.mozilla.org/en-US/docs/Mozilla/Add-ons/WebExtensions/Native_messaging).

`com.newpgp.bridge.json` in this folder is a **template** for manual installs.
Replace the placeholders with the absolute executable path and your real ID(s),
keep only the fields you use, then on Windows create the registry key whose
(Default) value points at the manifest file — copying the JSON into a browser
folder alone is not enough. Chrome/Edge use `allowed_origins`, Firefox uses
`allowed_extensions`; an ID must match exactly or the browser refuses to launch
the helper.

## Protocol

Requests: `{ "id": "...", "method": "...", "params": { ... } }`
Responses: `{ "id": "...", "ok": true, "result": { ... } }` or
`{ "id": "...", "ok": false, "error": { "code": "...", "message": "..." } }`

Supported methods (eight, implemented in `crates/newpgp-cli/src/main.rs`):
`ping`, `generateKey`, `encrypt`, `decrypt`, `sign`, `verify`, `getKey`,
`discoverKey`. The TypeScript contracts live in
`extensions/browser/src/types.ts`.

`generateKey` takes `name`, `email` and an optional `passphrase`. It stores the
key in the local user data folder and returns `fingerprint`, `publicKey` armor,
**`privateKey` armor** and a safety number. That passphrase is entered by the
user locally in the installed NewPGP app (native UI) and handed to the helper by
the extension's background service worker; a web page or content script must
never collect, display or send it. The same applies to any private-key material:
`privateKey` armor stays inside the local app/extension flow and is not stored
by the extension or exposed to pages.

## Security notes

- **The bridge itself does not authenticate its caller.** It simply processes
  framed JSON from stdin. Access control is enforced by the **browser**, which
  checks the manifest's `allowed_origins` (Chrome/Edge) or `allowed_extensions`
  (Firefox) before connecting; an unlisted extension cannot launch it through
  native messaging. The binary can still be launched directly by any local
  process with access to the user's account, so treat local machine security as
  part of the trust boundary.
- The extension's service worker must validate `sender.origin` (or
  `sender.url`) and sanitize payloads before forwarding messages from content
  scripts; content scripts share a renderer with untrusted web pages. See the
  warning in [Chrome native messaging](https://developer.chrome.com/docs/extensions/develop/concepts/native-messaging).
- Never ask a web page for a setup code, private-key password or passphrase;
  those are entered locally in the app. Never send them over the network.
- Drafts typed in webmail may still be autosaved by the provider before
  encryption. Use the built-in client for high-sensitivity conversations.
