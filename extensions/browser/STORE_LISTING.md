<!-- SPDX-License-Identifier: GPL-3.0-or-later -->
<!-- Copyright (C) 2026 Vaibhav - https://github.com/thisisforlearn -->

# Store listing copy — NewPGP for Webmail

Use this text for the Chrome Web Store, Microsoft Edge Add-ons, and addons.mozilla.org (AMO).
The extension is **free and open source** under GPL-3.0-or-later.

## Name

NewPGP for Webmail

## Short description

Add OpenPGP to Gmail and Outlook: send a readable message plus an encrypted copy by default, discover contacts' keys, and decrypt in place. Local helper required.

## Long description

NewPGP lets you write a normal email in Gmail or Outlook and still send an OpenPGP-encrypted copy — without asking you to change how you write.

**Send both (the default).** Your message stays exactly as you typed it. An armored PGP block is appended underneath, so people who do not use PGP can still read your email, while people who do can decrypt it. A clearly labelled switch turns on **PGP only**, which sends just the encrypted block. If encryption fails for any reason, NewPGP blocks the send and explains why — it never silently falls back to plaintext.

**Key discovery.** Enter a contact's email address and NewPGP asks the local helper to look for a public key through WKD, keys.openpgp.org, or an Autocrypt header. You see the fingerprint, a short safety number, and a plain-language reminder to compare it out of band.

**Decrypt in place.** Incoming `-----BEGIN PGP MESSAGE-----` blocks get a "Decrypt with NewPGP" button and a signature status: Verified, Unverified, or Invalid.

**Your privacy.** The extension stores only your preferences; it never stores message text, keys, or decrypted text. There is no analytics or telemetry. All cryptography happens in a helper on your own computer, not in the cloud. Private keys never enter the browser.

## Before you install

NewPGP needs a local native messaging helper (`com.newpgp.bridge`). The browser starts it automatically when the extension asks for it; there is no background service to keep running. You must register it once by hand:

1. Build or download the `newpgp` CLI from the project repository.
2. Note your extension ID (Chrome/Edge show it on `chrome://extensions`; this Firefox add-on uses `newpgp@newpgp.local`).
3. Run `native-host/install.ps1` on Windows or `native-host/install.sh` on macOS/Linux with your real extension ID and the path to the CLI.
4. Restart the browser and reload the extension. The popup should say "Helper connected".

The ID in the native host manifest must match exactly, or the browser refuses to launch the helper. See the repository README for full steps.

## Permissions explained

- **nativeMessaging** — to talk to the local helper that does the cryptography.
- **storage** — to remember your send mode and the "understood autosave" acknowledgement. No message text is stored.
- **Host access to `mail.google.com` and Outlook on the web** — so the content script can read your compose draft and add the encrypted block. No broad `<all_urls>` access is requested.

## Categories

- Chrome Web Store: **Social & Communication** (secondary: Productivity)
- Microsoft Edge Add-ons: **Social & Communication**
- AMO: **Privacy & Security** (secondary: Productivity)

## Keywords

`pgp`, `openpgp`, `encryption`, `email`, `gmail`, `outlook`, `privacy`, `secure email`, `end-to-end encryption`, `gpg`, `key discovery`, `wkd`, `decrypt`, `send both`

## Screenshots (placeholders — capture from a real build before submission)

| File | Size | What to show |
| --- | --- | --- |
| `screenshots/01-popup.png` | 1280×800 | The popup: "Send safely, stay readable", helper connected, PGP-only toggle |
| `screenshots/02-send-both.png` | 1280×800 | A Gmail compose draft with the armored block appended underneath |
| `screenshots/03-discover-key.png` | 1280×800 | Key discovery result with fingerprint and safety number |
| `screenshots/04-decrypt.png` | 1280×800 | An incoming encrypted block with the Decrypt button and signature status |
| `screenshots/05-options.png` | 1280×800 | Options page including the native-host installation notes |

AMO also accepts a 900×600 tile and a 440×280 icon/marquee; the existing 128×128 PNG icon can be upscaled for the tile.

## Reviewer / test instructions (required for native messaging)

Include this verbatim in the Chrome Web Store "Test instructions" field:

> This extension requires a locally installed native messaging host. Build the `newpgp` CLI from the linked repository, then run `native-host/install.ps1` (Windows) or `native-host/install.sh` (macOS/Linux) with the extension ID shown on `chrome://extensions`. Restart Chrome and reload the extension. The popup should read "Helper connected". Use any Gmail or Outlook web compose window to test; paste an OpenPGP private key into the local CLI's keyring first. No account credentials are needed for review.

## Supporting URLs

- Homepage / source: https://github.com/thisisforlearn/newpgp
- Privacy policy: `PRIVACY.md` in this folder (publish at a public HTTPS URL for the store form)
- Contact: nekomeowpurr@proton.me
- License: GPL-3.0-or-later
- Contributor: @sudo-linux-neko
