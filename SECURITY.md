# Security Policy — NewPGP

## ⚠️ Read this first

**NewPGP v0.1 has not been independently audited.** It is built carefully on vetted libraries, but you should **not rely on it for life-or-death secrecy**. It protects the contents of messages using the OpenPGP standard; it does not hide metadata and does not provide forward secrecy. See [THREAT_MODEL.md](THREAT_MODEL.md) for the full picture.

## Supported versions

Security fixes are provided for the latest released version. Version 0.1 is an alpha: older versions may not receive fixes.

## Reporting a vulnerability

**Please do not open a public issue for security vulnerabilities.**

Report privately by email to **nekomeowpurr@proton.me** (please put "NewPGP security" in the subject), or use GitHub's private vulnerability reporting on <https://github.com/thisisforlearn/newpgp/security/advisories>. Do not post exploit details in public issues.

When reporting, please include:

- a description of the issue and its impact,
- steps to reproduce (or a proof of concept),
- affected version(s),
- any suggested fix (optional).

We will acknowledge reports as quickly as we can and coordinate a fix and disclosure with you. Please give us reasonable time to address the issue before public disclosure.

## Our security practices

- **No custom cryptography.** Everything goes through rPGP (the `pgp` crate) and well-reviewed libraries. Algorithms and wire formats come from RFC 9580 — <https://www.rfc-editor.org/rfc/rfc9580.html>.
- **Minimal code, heavy tests, and fuzzing**, especially for parsers and armored-block normalization. A 39C3 talk found 14 bugs in mature tools (GnuPG, Sequoia, age, minisign); we take that lesson seriously.
- **Safe S2K:** password-protected keys use Argon2id (preferred, memory-hard) or iterated+salted SHA-256/512 **key derivation**, with **AES** doing the actual encryption. Hashes are not encryption.
- **Careful key discovery:** WKD rules (advanced then direct, SHA-1+Z-Base-32 local-part hash, drop extra User-IDs, reject HTTP 401), verified-email keys from keys.openpgp.org, Autocrypt peer state, and safety numbers for out-of-band verification.

## Advice for users

- Use a **long, unique password** to unlock your private key. This password never goes online.
- **Back up your private key safely.** See [KEY_TRANSFER.md](docs/KEY_TRANSFER.md):
  - never reuse a backup password,
  - never send a password in the same message as the encrypted data,
  - prefer encrypting a backup to a **known public key** so no password must travel at all,
  - use **Argon2id with high memory**, and store backups **offline**.
- Compare **safety numbers** with friends out of band (a quick voice call is fine) to guard against first-use key substitution.
- Remember that **metadata is visible** (sender, recipient, subject, timestamp). Do not put sensitive information in the subject line.
- Remember there is **no forward secrecy in v0.1** — a stolen key could decrypt saved old messages.

## Known limitations (not vulnerabilities)

These are documented design limitations, not bugs:

- No forward secrecy in v0.1 (planned Phase 3 ratchet hybrid).
- SMTP metadata is not hidden.
- Not audited.
- "Undo send" is not true recall.

## References

- RFC 9580 — <https://www.rfc-editor.org/rfc/rfc9580.html>
- rPGP — <https://github.com/rpgp/rpgp>
- keys.openpgp.org — <https://keys.openpgp.org/>
- Autocrypt Level 1 — <https://docs.autocrypt.org/level1.html>
- age — <https://github.com/FiloSottile/age>
- Sequoia PGP — <https://sequoia-pgp.org/>
- Signal Double Ratchet — <https://signal.org/docs/specifications/doubleratchet/>
