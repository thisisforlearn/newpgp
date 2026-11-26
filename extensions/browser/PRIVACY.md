<!-- SPDX-License-Identifier: GPL-3.0-or-later -->
<!-- Copyright (C) 2026 Vaibhav - https://github.com/thisisforlearn -->

# NewPGP privacy policy (plain language)

**Last updated: 2026-11-26**

NewPGP is a browser extension that adds OpenPGP encryption to Gmail and Outlook on the web. This policy explains what it does with your information. It applies to the extension, not to your webmail provider.

## The short version

- We do **not** collect your messages, and we have no analytics or telemetry.
- The extension stores **only your settings** in browser storage. It never stores plaintext, decrypted text, or private keys.
- All encryption and decryption happen in a helper program on **your own computer**. Nothing is sent to a NewPGP server.
- The only network requests the extension causes are public-key lookups, made by the local helper.

## What stays on your device

### Drafts

When you click Send, the content script reads your draft from the compose box, holds it in memory, and hands it to the local helper to encrypt. In the default **Send both** mode your original words are put back into the draft with an encrypted block underneath. In **PGP only** mode the draft is replaced by the encrypted block. The extension does not write your draft to browser storage, a file, or a database.

### Settings

We use browser `storage` to remember exactly three things:

- your send mode (send both, or PGP only),
- whether NewPGP is enabled,
- whether you acknowledged the webmail draft-autosave notice.

No message text, key material, passphrases, or decrypted text is ever written there.

### Keys

Private keys live in the local helper's keyring, under your OS user account. The extension sees public keys and fingerprints only when you ask it to discover or use one. It never stores private key material.

## What the local helper does

NewPGP registers a native messaging host named `com.newpgp.bridge`. You install it yourself with the scripts in the repository (`native-host/install.ps1` on Windows, `native-host/install.sh` on macOS/Linux). The browser starts it automatically when the extension needs it; there is no daemon running in the background.

The helper receives your draft over a local standard-input/standard-output connection, performs the OpenPGP operation, and returns the result. Your message text is not saved by the bridge. The browser only launches the helper for the exact extension ID listed in the native host manifest.

## Network access

- **Key discovery.** If you look up a contact's key, the local helper may query WKD, `keys.openpgp.org`, or an Autocrypt header. These requests look up **public** keys; your message text and private keys are not uploaded.
- **Webmail.** Gmail and Outlook are the pages the extension runs on. Those providers have their own privacy policies and may autosave drafts before NewPGP encrypts them. NewPGP cannot control that. For highly sensitive mail, use the built-in NewPGP client instead of a webmail compose window.

The extension itself makes no analytics, advertising, crash-reporting, or telemetry requests.

## Permissions we ask for

| Permission | Why we need it |
| --- | --- |
| `nativeMessaging` | To talk to the local helper that performs encryption and decryption. |
| `storage` | To remember the three settings listed above. |
| `mail.google.com` and Outlook web hosts | So the content script can read your compose draft and insert the encrypted block. We do not ask for `<all_urls>`. |

`browser_specific_settings.gecko.data_collection_permissions.required` is declared as `none`: the extension collects and transmits no user data for storage or processing outside the extension.

## Your choices

- Turn NewPGP off at any time from the options page. When off, the extension leaves your drafts alone.
- Choose **PGP only** only when you are sure every recipient can decrypt; people without PGP cannot read those messages.
- Uninstall the extension at any time. It leaves no message text behind, because it never stored any. Removing the extension does not remove keys from the local helper's keyring; manage those with the CLI.

## Contact and license

Questions about privacy: nekomeowpurr@proton.me

Official project: https://github.com/thisisforlearn/newpgp

Contributor: @sudo-linux-neko

License: GPL-3.0-or-later. The source is public; you can audit exactly what the extension does.
