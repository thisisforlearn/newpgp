# NewPGP Architecture

This document explains how NewPGP is put together. It is written to be useful to both contributors and technically curious users. NewPGP is a suite of thin, friendly applications built around one shared, careful cryptography core.

## Guiding principles

1. **No custom crypto.** All cryptography goes through rPGP (the pure-Rust `pgp` crate, v0.21.1) and well-reviewed libraries. We do not design our own algorithms or wire formats.
2. **Standard only.** This stage implements OpenPGP (RFC 9580) and nothing else. No new protocol.
3. **Small, testable surface.** Minimal code, heavy unit/integration tests, and fuzzing of parsers and format handling.
4. **Honest defaults.** The friendly path is also the safe path, and limitations are documented rather than hidden.

## Component overview

```
                         ┌──────────────────────────┐
                         │      Shared Rust core    │
                         │  (pgp crate v0.21.1)     │
                         │  keys, encrypt/decrypt,  │
                         │  sign/verify, discovery  │
                         └────────────┬─────────────┘
                                      │  FFI / shared library
        ┌─────────────┬───────────────┼───────────────┬──────────────┐
        │             │               │               │              │
┌───────▼──────┐ ┌─────▼──────┐ ┌──────▼──────┐ ┌──────▼─────┐ ┌──────▼──────┐
│ Built-in     │ │ Browser    │ │ Mobile app  │ │ Android    │ │ Search /    │
│ mail client  │ │ extension  │ │ (iOS/Android)│ │ keyboard   │ │ local store │
│ IMAP/SMTP    │ │ Gmail/     │ │ wrapper     │ │ (Kotlin    │ │ (encrypted) │
│              │ │ Outlook   │ │             │ │ IME)       │ │             │
└──────────────┘ └────────────┘ └─────────────┘ └────────────┘ └─────────────┘
```

Each platform app is a thin layer over the same core, so behavior (key handling, encryption, normalization) stays consistent everywhere.

## 1. Cryptography core (Rust)

- **Library:** rPGP, the pure-Rust `pgp` crate (v0.21.1). See <https://github.com/rpgp/rpgp>.
- **Standard:** OpenPGP RFC 9580 only — <https://www.rfc-editor.org/rfc/rfc9580.html>.
- **Keys:** v4 and v6 keys. Primary keys are **Ed25519** signing keys; encryption subkeys are **Curve25519**. **AEAD** is used where available.
- **No custom crypto:** hashing, symmetric encryption, public-key operations, and packet parsing all come from vetted libraries. NewPGP supplies policy and workflow, not algorithms.

### Password-protected keys (S2K)

Secret keys stored on a device are protected with RFC 9580's String-to-Key (S2K) mechanisms. Two families are supported:

- **Iterated + salted SHA-256 / SHA-512** (classic RFC 9580 S2K).
- **Argon2id** (memory-hard) where supported.

> **Important clarification — hashes are not encryption.** SHA-256 and SHA-512 are *hash functions*. They cannot "encrypt with one password." The correct construction is:
>
> 1. Derive a strong symmetric key from your password using a **key-derivation function** — Argon2id (preferred, memory-hard) or iterated+salted S2K.
> 2. **Encrypt** the secret key bytes with a real cipher (AES) using that derived key.
>
> In short: *Argon2id derives the key; AES does the encrypting.* SHA-256/512 may appear as part of the derivation/salt process, but they never encrypt anything by themselves. NewPGP follows this construction exactly; there is no "hash then hash again" scheme.

## 2. Key discovery layer

NewPGP finds public keys automatically so users do not have to hunt for them. Order of operations matters:

1. **WKD (Web Key Directory)** — draft-koch-openpgp-webkey-service-22 — <https://datatracker.ietf.org/doc/html/draft-koch-openpgp-webkey-service>.
   - Try the **advanced method first**, then fall back to the **direct method**.
   - The local-part hash uses **SHA-1 + Z-Base-32** (this is a *lookup identifier*, not a security decision).
   - **MUST drop extra User-IDs** returned by WKD (only the requested identity is accepted).
   - **MUST NOT accept HTTP 401** as a valid response — a 401 means "not available here," not "here is a key."
2. **keys.openpgp.org** — <https://keys.openpgp.org/>.
   - Only keys whose email addresses have been **verified** are listed.
   - The service **strips third-party signatures**, so looking up a key does not reveal your social graph.
3. **Autocrypt 1.1** — <https://docs.autocrypt.org/level1.html>.
   - In-band key distribution via email headers.
   - Maintains **peer state** per correspondent.
   - Honors `prefer-encrypt=mutual` before enabling opportunistic encryption.
   - Supports **key gossip** in headers.
   - Writes **encrypted drafts to self** so mail providers cannot autosave plaintext.
4. **Invite links** — for people without any published key, share an invite link carrying a **fingerprint / safety number** so the recipient can be identified safely.

### Trust model

NewPGP uses **Trust On First Use (TOFU)** plus **short safety numbers** that two people can compare out of band. It deliberately does **not** rely on the Web of Trust or key signing parties, which create heavy social friction for non-technical users.

## 3. Built-in mail client (IMAP/SMTP)

- Standard IMAP/SMTP for compatibility with existing email providers.
- **Drafts are encrypted to self** before being saved/uploaded, so the mail provider never sees a plaintext draft.
- Incoming encrypted mail is decrypted locally using the private key.
- **Search** is over a **local encrypted index**; decrypted content stays on the device. There is no server-side search and no plaintext sent to any service.
- "Undo send" is implemented as: a **local plaintext copy saved at send time**, plus a **short cancel-send window where the mail server supports it**. It cannot truly recall an email that has already arrived. (See [FAQ.md](docs/FAQ.md).)
- **Files:** OpenPGP encrypts arbitrary bytes, so images, video, and attachments work the same way as text. NewPGP preserves the **filename and MIME type** in packet metadata so attachments remain usable after decryption.

## 4. Browser extension (Gmail / Outlook)

- Reads and writes mail inside the webmail UI.
- **Default behavior:** send the **normal plaintext email** *and* append the PGP armored block. Non-PGP friends can still read the message, and PGP users gradually get used to the format. A **toggle switches to PGP-only**.
- **Never deletes or replaces the user's draft body by default.** NewPGP appends, rather than overwrites, so a user's writing is never lost.
- **Formatting robustness:** before parsing, armored blocks are normalized to undo real-world damage:
  - strip `>` quoting markers,
  - unwrap HTML wrappers,
  - remove trailing spaces,
  - repair common line-break damage.

This normalization is important because webmail clients frequently mangle armored text.

## 5. Mobile apps and Android keyboard

- **Mobile apps** wrap the shared core for iOS and Android.
- **Android keyboard (Kotlin IME):**
  - Strong autocorrect and autocapitalization for a normal typing experience.
  - A dedicated **"encrypt" key** encrypts the current composing text on tap.
  - An **OPTIONAL auto-append mode** adds an encrypted block to messages, gated behind an **explicit toggle** with a **visible indicator and warning**.
  - Rationale for the toggle: an IME that silently captures every keystroke is a serious privacy risk, and an IME **cannot observe "send" events**. Auto-append must therefore be opt-in and clearly advertised, never silent.

## 6. Data flow (simplified)

**Sending**
1. Compose text (mail client, extension, or keyboard).
2. Discover recipient public key (WKD → keys.openpgp.org → Autocrypt → invite link).
3. Encrypt (and sign) via rPGP; save an encrypted draft to self.
4. Send: plaintext+armored by default in the extension, or PGP-only when toggled.

**Receiving**
1. Fetch message (IMAP or webmail).
2. Normalize any armored block (extension path).
3. Decrypt and verify with the private key.
4. Store in the local encrypted index; display decrypted content.

## 7. Non-goals for this stage

- No new cryptographic protocol.
- No Web of Trust / key signing party workflow.
- No forward secrecy (planned Phase 3 as a Signal Double Ratchet hybrid — <https://signal.org/docs/specifications/doubleratchet/>; out of scope for v0.1).
- No hiding of SMTP metadata (sender, recipient, subject, timestamp).
- No server-side or cloud search.

## References

- RFC 9580 — <https://www.rfc-editor.org/rfc/rfc9580.html>
- WKD draft — <https://datatracker.ietf.org/doc/html/draft-koch-openpgp-webkey-service>
- keys.openpgp.org — <https://keys.openpgp.org/>
- Autocrypt Level 1 — <https://docs.autocrypt.org/level1.html>
- rPGP — <https://github.com/rpgp/rpgp>
- age — <https://github.com/FiloSottile/age>
- Sequoia PGP — <https://sequoia-pgp.org/>
- Signal Double Ratchet — <https://signal.org/docs/specifications/doubleratchet/>
