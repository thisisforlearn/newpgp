# Issues Addressed

This table maps every problem the project set out to solve to its honest status. "Solved" means the v0.1 design addresses it; "Mitigated" means we reduce but do not eliminate it; "Deferred" means planned for a later phase; "Out of scope" means OpenPGP alone cannot solve it in this stage.

We have tried hard not to overclaim — especially on metadata, forward secrecy, and the "nobody else uses it" problem.

## Category 1 — Cryptography & key management

| # | Issue | Status | How |
| --- | --- | --- | --- |
| 1 | "Encrypt with SHA-512, then SHA-256, with one password" | **Solved** | Corrected the model: hashes are not encryption. RFC 9580 S2K uses Argon2id (or iterated+salted SHA-256/512) to *derive* a key, then **AES** to encrypt. No hash-on-hash scheme. |
| 2 | Protecting keys on device with a password | **Solved** | Password-protected keys use RFC 9580 S2K — Argon2id or iterated+salted SHA-256/512 — with real AES encryption. |
| 3 | Risk of custom/home-grown crypto | **Solved** | No custom crypto. All operations go through rPGP (`pgp` crate v0.21.1) and vetted libraries; OpenPGP RFC 9580 only. |
| 4 | Transferring a key/password without interception or meeting in person | **Solved** | Default is to encrypt a backup to the recipient's already-known public key (no password travels); otherwise ciphertext + random 36-digit code out-of-band; QR in person is best. See [KEY_TRANSFER.md](KEY_TRANSFER.md). |
| 5 | Mature tools still have bugs (39C3: 14 bugs in GnuPG, Sequoia, age, minisign) | **Mitigated** | Minimal code, no custom crypto, heavy tests, and fuzzing of parsers/normalization. Risk reduced, not eliminated. |
| 6 | Independent security audit | **Deferred** | Planned for Phase 1. v0.1 is explicitly **not audited** — do not rely on it for life-or-death secrecy. |

## Category 2 — Key discovery & trust

| # | Issue | Status | How |
| --- | --- | --- | --- |
| 7 | Non-technical users cannot find or manage keys | **Solved** | Automatic discovery via WKD, keys.openpgp.org, Autocrypt, and invite links; plain-language UX. |
| 8 | WKD must be correct and safe | **Solved** | Advanced method first then direct, SHA-1+Z-Base-32 local-part hash, **drop extra User-IDs**, **reject HTTP 401**. |
| 9 | Public key servers leaking the social graph | **Solved** | keys.openpgp.org lists only verified emails and strips third-party signatures, so lookups do not reveal contacts. |
| 10 | Web of Trust / key signing parties are too much friction | **Mitigated** | Replaced with TOFU + short safety numbers for optional out-of-band verification. Less rigorous, far more usable. |
| 11 | "Nobody I know uses PGP" | **Mitigated** | Send-both default and Autocrypt opportunistic encryption mean you never force anyone to install anything; adoption grows naturally. Honest: it reduces friction, it does not magically create users. |
| 12 | People with no published key are unreachable | **Solved** | Invite links carrying a fingerprint/safety number let you identify and onboard them. |

## Category 3 — Email UX & platforms

| # | Issue | Status | How |
| --- | --- | --- | --- |
| 13 | Extension deleting/replacing the user's draft body | **Solved** | By default the encrypted block is **appended**; the user's writing is never deleted or overwritten. |
| 14 | Non-PGP friends cannot read encrypted mail | **Solved** | Browser extension default sends the normal plaintext email **and** appends the PGP armored block. |
| 15 | Users who want real encryption need an option | **Solved** | A toggle switches the extension to **PGP-only**. |
| 16 | Webmail mangling armored blocks (quoting, HTML, spaces, line breaks) | **Solved** | Armored blocks are normalized before parsing: strip `>` quoting, unwrap HTML, trim trailing spaces, repair line-break damage. |
| 17 | "Undo" for sent messages | **Mitigated** | Cannot truly recall an email. Provide a local plaintext copy saved at send time and a short cancel-send window where the server supports it, with an honest explanation. |
| 18 | Searching encrypted messages | **Solved** | Local encrypted index; decryption and search happen on-device only. No server-side search. |
| 19 | Images, video, and attachments | **Solved** | OpenPGP encrypts arbitrary bytes; files work like text, with filename and MIME type preserved in packet metadata. |
| 20 | Webmail providers autosaving plaintext drafts | **Solved** | Built-in mail client saves **drafts encrypted to self**, so providers never see a plaintext draft. |

## Category 4 — Security, platforms & honesty

| # | Issue | Status | How |
| --- | --- | --- | --- |
| 21 | Android keyboard silently capturing all keystrokes / cannot see "send" | **Mitigated** | Explicit "encrypt" key on tap; auto-append mode is off by default, behind an explicit toggle with a visible indicator and warning. An IME cannot observe send events, so silent capture is avoided. |
| 22 | Forward secrecy | **Deferred** | Classic OpenPGP has none. A hybrid Signal Double/Triple Ratchet design is a Phase 3 item and **out of scope for v0.1** — we do not claim it. |
| 23 | Metadata (sender, recipient, subject, timestamp) | **Out of scope** | OpenPGP does not hide SMTP envelopes. Honestly documented in [THREAT_MODEL.md](../THREAT_MODEL.md); do not put secrets in the subject line. |

> Note: the table lists 23 rows across four categories; the forward-secrecy and metadata rows are separated because they are the two limitations we most want to be explicit about.

## Summary counts

- **Solved:** 15
- **Mitigated:** 5
- **Deferred:** 2
- **Out of scope:** 1

## References

- RFC 9580 — <https://www.rfc-editor.org/rfc/rfc9580.html>
- rPGP — <https://github.com/rpgp/rpgp>
- WKD draft — <https://datatracker.ietf.org/doc/html/draft-koch-openpgp-webkey-service>
- keys.openpgp.org — <https://keys.openpgp.org/>
- Autocrypt Level 1 — <https://autocrypt.org/level1.html>
- Signal Double Ratchet — <https://signal.org/docs/specifications/doubleratchet/>
