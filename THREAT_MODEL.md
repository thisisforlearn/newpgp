# NewPGP Threat Model

This document states plainly what NewPGP protects, what it does not, and who it is (and is not) designed to defend against. We publish this so you can make an informed decision — not to claim perfection.

> **Bottom line for v0.1:** NewPGP protects the *contents* of your messages and attachments against many common attackers. It does **not** hide metadata, does **not** provide forward secrecy, and has **not** been audited. Do not rely on it for life-or-death secrecy in v0.1.

## Assets we try to protect

| Asset | How it is protected |
| --- | --- |
| Message contents | OpenPGP encryption (RFC 9580) via rPGP |
| Attachments (files, images, video) | Encrypted as arbitrary bytes, same as text |
| Private key at rest | RFC 9580 S2K: Argon2id or iterated+salted SHA-256/512 derivation, then AES encryption |
| Drafts (built-in client only) | Drafts saved by the built-in NewPGP mail client are encrypted to self. The browser extension's default **send-both** mode deliberately includes the plaintext body in the sent message; see below. |
| Search history / decrypted index | Stored locally, encrypted; no server-side search |
| Your social graph | keys.openpgp.org usage avoids third-party-signature leaks |

## Adversaries we consider

- **Passive network eavesdropper** (someone on your Wi-Fi, an ISP observer): message contents are ciphertext.
- **Mail provider / webmail service**: messages sent in **PGP-only** mode from the built-in client are ciphertext to the provider, and drafts saved by that client are encrypted to self. This does **not** hold for the browser extension's default **send-both** mode, which deliberately sends the plaintext body alongside the armored block. In all cases the provider sees metadata (see below).
- **Someone who steals a backup file**: protected by a strong password derived with Argon2id and encrypted with AES.
- **An attacker who tampers with a key lookup**: mitigated by WKD rules, TOFU, and safety numbers you can compare out of band.

## What is NOT protected (honest limitations)

### Metadata is visible

OpenPGP encrypts the message body, not the envelope. SMTP still exposes:

- sender address,
- recipient address(es),
- subject line,
- timestamp,
- often message size and routing information.

NewPGP cannot solve this with OpenPGP alone. If metadata secrecy matters to you, this tool is not sufficient.

### The extension's send-both default exposes the plaintext body

This is a deliberate design choice, not a bug. The browser extension's default
**send-both** mode sends your message as written (**plaintext**) followed by the
OpenPGP armored block, so friends who do not use PGP can still read your mail.
That means the webmail provider — and anyone able to read the stored message on
its servers — can read the plaintext body. Switching the extension to **PGP
only** removes the plaintext body, but then non-PGP recipients cannot read it.

**Encrypted drafts-to-self apply to the built-in NewPGP mail client only.** The
extension does not delete or encrypt drafts in Gmail/Outlook, and those services
may autosave a draft before NewPGP encrypts it. For sensitive conversations, use
the built-in client and choose PGP-only mode.

### No forward secrecy

Classic OpenPGP has **no forward secrecy**. If an attacker records your encrypted traffic today and later steals your private key, they can decrypt those old messages. This is an acknowledged limitation of the standard, not a bug in NewPGP.

A hybrid design using the **Signal Double Ratchet** (<https://signal.org/docs/specifications/doubleratchet/>) is a **Phase 3** design item and is **explicitly out of scope for v0.1**. We do not claim a property we do not have.

### Not audited; implementation bugs are real

A talk at **39C3** found **14 bugs** across mature tools (GnuPG, Sequoia, age, minisign). The lesson is that even widely used implementations have defects. Our mitigations:

- **no custom crypto** — rely on rPGP and vetted libraries,
- **minimal code** — small surface area,
- **heavy tests** and **fuzzing** of parsers and armored-block normalization,
- **responsible disclosure** process (see [SECURITY.md](SECURITY.md)).

These reduce risk but do not eliminate it. **NewPGP v0.1 is not independently audited.**

### "Trust on First Use" has a first-use window

TOFU means the first time you see a key, NewPGP trusts it. If an attacker substitutes a key at that exact first moment, they could impersonate the correspondent. **Short safety numbers** let you verify out of band and close this gap. We choose TOFU over the Web of Trust because key signing parties create too much friction for non-technical users — a deliberate tradeoff, documented in [ISSUES_ADDRESSED.md](docs/ISSUES_ADDRESSED.md).

### The Android keyboard tradeoff

The optional **auto-append mode** adds a ciphertext block to messages. An IME that silently captured all keystrokes would be a serious privacy risk, and an IME cannot see "send" events, so it cannot know when a message is finished. Therefore:

- auto-append is **off by default**,
- it requires an **explicit toggle**,
- it shows a **visible indicator and warning** while active.

The dedicated **encrypt key** (encrypt on tap) is the safe, explicit path. See [ARCHITECTURE.md](ARCHITECTURE.md).

### Undo is not real recall

NewPGP cannot pull back an email that has already arrived at the recipient. "Undo send" is a **local plaintext copy saved at send time** plus a **short cancel-send window where the server supports it**. Treat it as a convenience, not a guarantee. See [FAQ.md](docs/FAQ.md).

## Key and password handling

- Your **password** unlocks your private key locally; it is not the password used to encrypt messages to others.
- **Never reuse a backup password**, and never send a password in the same message as the ciphertext it protects.
- Use **Argon2id with high memory** for backups, and store backups **offline**.
- Moving a key/password between people requires a pre-shared secret or a trusted channel — there is no way around this. See [KEY_TRANSFER.md](docs/KEY_TRANSFER.md).

## Social-engineering risk

Encryption cannot protect you if you are tricked into giving away your password or verifying the wrong safety number. NewPGP's friendly defaults (send-both, TOFU, invite links) are designed to reduce friction and increase adoption — not to replace careful judgment.

## Summary

| Threat | Status |
| --- | --- |
| Eavesdropper reading message contents | Protected (OpenPGP) |
| Mail provider reading plaintext bodies | Built-in client PGP-only messages and drafts-to-self are ciphertext; extension send-both exposes the body by design |
| Key lookup tampering | Mitigated (WKD rules, TOFU, safety numbers) |
| Social graph leak via key server | Mitigated (keys.openpgp.org strips third-party sigs) |
| Metadata (who/when/subject) | **Not protected** |
| Forward secrecy | **Not protected in v0.1** (Phase 3) |
| Implementation bugs | **Mitigated, not eliminated; not audited** |
| "Nobody else uses it" | **Mitigated** via send-both + Autocrypt (see ISSUES_ADDRESSED.md) |

## References

- RFC 9580 — <https://www.rfc-editor.org/rfc/rfc9580.html>
- rPGP — <https://github.com/rpgp/rpgp>
- Autocrypt Level 1 — <https://docs.autocrypt.org/level1.html>
- keys.openpgp.org — <https://keys.openpgp.org/>
- Signal Double Ratchet — <https://signal.org/docs/specifications/doubleratchet/>
- age — <https://github.com/FiloSottile/age>
- Sequoia PGP — <https://sequoia-pgp.org/>
