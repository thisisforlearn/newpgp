# NewPGP Roadmap

This is a living plan. Dates are deliberately absent — we will ship when each phase is ready and tested. Status legend: **Done** / **In progress** / **Planned** / **Deferred** / **Out of scope**.

## Phase 0 — Foundation (v0.1)

Goal: a friendly, standards-compliant OpenPGP suite that a non-technical person can actually use.

- [In progress] Rust core built on rPGP (`pgp` crate v0.21.1), OpenPGP RFC 9580 only.
- [In progress] v4/v6 keys: Ed25519 signing + Curve25519 encryption subkeys, AEAD where available.
- [In progress] Password-protected keys via RFC 9580 S2K (Argon2id or iterated+salted SHA-256/512 + AES).
- [In progress] Key discovery: WKD (advanced → direct, SHA-1+Z-Base-32, drop extra User-IDs, reject HTTP 401), keys.openpgp.org, Autocrypt 1.1, invite links.
- [In progress] TOFU + short safety numbers.
- [In progress] Built-in IMAP/SMTP mail client with drafts encrypted to self.
- [In progress] Browser extension for Gmail/Outlook: send-both default, PGP-only toggle, no draft deletion, armored-block normalization.
- [In progress] Local encrypted index and local-only search.
- [In progress] File/attachment encryption with filename + MIME preserved.
- [In progress] Android keyboard (Kotlin IME): encrypt key, strong autocorrect, optional auto-append behind toggle + indicator/warning.
- [In progress] "Undo send": local plaintext copy + short cancel-send window where supported.
- [In progress] Heavy tests, fuzzing, minimal code; security policy and disclosure process.

> **v0.1 caveat:** not audited; no forward secrecy; metadata not hidden. See [SECURITY.md](SECURITY.md) and [THREAT_MODEL.md](THREAT_MODEL.md).

## Phase 1 — Polish and trust

- [Planned] Independent security audit of the core (funding/community permitting).
- [Planned] Reproducible builds and signed release artifacts.
- [Planned] More complete WKD coverage and better diagnostics when discovery fails.
- [Planned] Expanded invite-link UX for people with no published key.
- [Planned] Accessibility and localization review.
- [Planned] More Autocrypt peer-state and gossip refinements.

## Phase 2 — Deeper platform integration

- [Planned] **`newpgp-mobile` + UniFFI:** a shared Rust crate exposing `newpgp-core`, `newpgp-keyring` and `newpgp-discovery` to Swift/Kotlin through generated UniFFI bindings, so mobile apps use the same engine as the CLI and desktop app.
- [Planned] **iOS real bridge:** replace the fail-closed `RustPgpBridge` stub in the SwiftUI shell with those generated bindings; see [ios/README.md](ios/README.md). The in-memory mock must never ship in a release build.
- [Planned] **Android native bridge integration:** build `newpgp-core` for Android ABIs and wire the generated Kotlin bindings and `.so` files into the companion app's `NativePgpBridge` and the keyboard IME's `NativePgpBridge`, with Android Keystore key storage and recipient selection; see [android/app/README.md](android/app/README.md) and [android/keyboard/README.md](android/keyboard/README.md).
- [Planned] Broader mail-client and webmail support beyond Gmail/Outlook.
- [Planned] Improved backup/restore flows following [KEY_TRANSFER.md](docs/KEY_TRANSFER.md) guidance.
- [Planned] Optional PAKE-based key transfer (OPAQUE/SPAKE2) for two parties online at the same time.
- [Planned] Key rotation and expiry handling with clear user guidance.

> **Mobile status today:** iOS and Android have UI shells and bridge contracts,
> but no real cryptography. Mock bridges produce clearly labelled fake armor in
> tests/local demos only, and the default native bridges **fail closed**. There
> is no shipped mobile crypto until the items above land.

## Phase 3 — Forward secrecy (explicitly NOT in v0.1)

- [Deferred] Design a hybrid mode combining OpenPGP identity keys with a **Signal Double/Triple Ratchet** for message contents — <https://signal.org/docs/specifications/doubleratchet/>.
- [Deferred] Carefully specify how ratchet sessions coexist with standard OpenPGP so we remain interoperable.
- [Deferred] Audit before shipping.

> We will not claim forward secrecy until it is designed, implemented, tested, and audited. Classic OpenPGP does not provide it, and v0.1 does not either.

## Out of scope

- [Out of scope] Inventing a new cryptographic protocol — NewPGP uses OpenPGP (RFC 9580) for this stage.
- [Out of scope] Hiding SMTP metadata (sender, recipient, subject, timestamp) with OpenPGP alone.
- [Out of scope] Web of Trust / key signing parties as the primary trust model (we use TOFU + safety numbers).
- [Out of scope] Anonymity / traffic analysis resistance.
- [Out of scope] Server-side or cloud search.

## Status summary

| Area | Current phase |
| --- | --- |
| Core crypto + key discovery + mail UX | Phase 0 (v0.1) |
| Audit & release hardening | Phase 1 |
| Backup/transfer UX & PAKE | Phase 2 |
| Mobile apps (`newpgp-mobile`/UniFFI, iOS + Android bridges) | Phase 2 (planned; mocks/stubs fail closed) |
| Forward secrecy (ratchet hybrid) | Phase 3, deferred |
| New crypto protocol, metadata hiding | Out of scope |

## References

- RFC 9580 — <https://www.rfc-editor.org/rfc/rfc9580.html>
- rPGP — <https://github.com/rpgp/rpgp>
- WKD draft — <https://datatracker.ietf.org/doc/html/draft-koch-openpgp-webkey-service>
- keys.openpgp.org — <https://keys.openpgp.org/>
- Autocrypt Level 1 — <https://docs.autocrypt.org/level1.html>
- Signal Double Ratchet — <https://signal.org/docs/specifications/doubleratchet/>
