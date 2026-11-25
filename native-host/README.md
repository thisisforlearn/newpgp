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
3. Run `install.ps1` (Windows) or `install.sh` (macOS/Linux) with that ID.
4. Restart the browser and reload the extension.

Or edit `com.newpgp.bridge.json` by hand and place it in the browser's
`NativeMessagingHosts` directory. Chrome/Edge use `allowed_origins`, Firefox uses
`allowed_extensions`; an extension ID must match exactly or the browser will
refuse to launch the helper.

## Protocol

Requests: `{ "id": "...", "method": "...", "params": { ... } }`
Responses: `{ "id": "...", "ok": true, "result": { ... } }` or
`{ "id": "...", "ok": false, "error": { "code": "...", "message": "..." } }`

Supported methods: `ping`, `generateKey`, `encrypt`, `decrypt`, `sign`,
`verify`, `getKey`, `discoverKey`. The TypeScript contracts live in
`extensions/browser/src/types.ts`.

## Security notes

- The bridge only accepts connections from the exact extension IDs in the manifest.
- Never put a setup code or secret key password in a bridge request; those are
  entered locally in the app, not supplied by web pages.
- Drafts typed in webmail may still be autosaved by the provider before
  encryption. Use the built-in client for high-sensitivity conversations.
