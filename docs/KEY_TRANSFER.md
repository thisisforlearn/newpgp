# How to transfer a password or key safely

> **The question:** *"How do I transfer the password/key without interception and without meeting in person?"*

This is one of the most important questions in all of encryption, and the honest answer starts with a hard rule.

## The fundamental rule

**You cannot securely send a secret over an insecure channel unless you already share a secret, or you use a channel you already trust.**

If you have never shared anything private with someone and all your channels are insecure, there is no magic trick that lets you hand them a secret safely. Anyone claiming otherwise is either assuming a trusted channel you did not realize you had, or is not being straight with you.

The good news: you usually **do** have something — a public key, an email address you have verified, a phone number, or a chance to scan a QR code. The options below go from best to most conditional.

---

## Option A (recommended): encrypt the backup to their ALREADY-KNOWN public key

**No password needs to travel at all.**

If you already know the recipient's public key — from **WKD**, an **Autocrypt** header, a **verified fingerprint**, or keys.openpgp.org — then:

1. Export your private key / backup file.
2. **Encrypt that backup file to the recipient's public key** (and sign it with yours).
3. Send the encrypted file through any channel — email, cloud storage, chat. It is ciphertext; interception does not matter.

Because the recipient decrypts with a key they already hold, there is **no shared password to leak**. This is the cleanest answer to the question and should be your default.

> Tip: verify the public key's fingerprint/safety number out of band first (a quick voice call) so you are encrypting to the right person.

---

## Option B: encrypted key file + setup code out-of-band (Autocrypt Setup Message pattern)

If you cannot encrypt to their public key, use the pattern Autocrypt uses for setup messages:

1. Generate a **random** setup code — **36 digits, formatted as numeric9x4** (nine groups of four digits), which is roughly **119 bits** of entropy. Do not choose it yourself; let the software generate it.
2. Encrypt the key file with a key derived from that code (Argon2id with high memory, then AES encryption).
3. Send the **ciphertext** through any channel — email, cloud storage, chat.
4. Deliver the **code through a different channel**:
   - a voice call read aloud,
   - an SMS,
   - a QR code scanned in person,
   - or hand it to them directly.
5. **Never put the code in the same message as the ciphertext.** If both travel together, an interceptor gets everything.

### Phishing mitigation

NewPGP **pre-fills the first 2 digits** of the setup code for you. This is a small but useful anti-phishing measure: it anchors the user to a value that came from the app, making it harder for an attacker to trick them into entering a full attacker-chosen code. It is a mitigation, not a complete defense — the rest of the code still must arrive out-of-band.

---

## Option C: QR code / in-person scan (best security)

Scanning a QR code while standing next to the other person gives the strongest assurance:

- both devices are physically present,
- the key or setup code moves through a short-range, visible channel,
- there is no opportunity for a network interceptor.

If you can meet in person, this is the best option. NewPGP supports sharing fingerprints/safety numbers as QR codes and can use QR scanning to hand off setup material.

---

## Option D: PAKE (OPAQUE / SPAKE2) — later phase

If both of you share a **short secret** and can be **online at the same time**, a Password-Authenticated Key Exchange (PAKE) such as **OPAQUE** or **SPAKE2** lets you establish a strong session key from that short secret without exposing it to eavesdroppers — even if an attacker is watching.

Caveats:

- **Both parties must be online simultaneously.**
- It still assumes you shared the short secret through some trusted channel.
- This is a **Phase 2** design item in [ROADMAP.md](../ROADMAP.md) and is **not** part of v0.1.

---

## Warnings — please read

- **Never reuse a backup password.** A password used elsewhere is only as strong as its weakest use.
- **Never send a password in the same email as the ciphertext it protects.** That is like mailing a locked box with the key taped to it.
- **Use Argon2id with high memory** for password-derived backups, not a plain hash. SHA-256/512 are hashes, not encryption; the correct construction is Argon2id to derive a key, then AES to encrypt. See [ARCHITECTURE.md](../ARCHITECTURE.md).
- **Store backups offline** (for example, on a USB drive kept somewhere safe). A backup that is permanently online is permanently exposed to theft.
- **If you lose your private key and have no backup, encrypted mail cannot be recovered.** Back up responsibly.

---

## Which option should I choose?

| Situation | Best option |
| --- | --- |
| You already know their public key | **A** — encrypt backup to their key; no password travels |
| You can meet in person | **C** — scan a QR code |
| You can talk by phone/SMS but not meet | **B** — ciphertext one way, 36-digit code the other |
| You share a short secret and are both online (future) | **D** — PAKE, once shipped |

## References

- RFC 9580 — <https://www.rfc-editor.org/rfc/rfc9580.html>
- Autocrypt Level 1 (Setup Message pattern) — <https://autocrypt.org/level1.html>
- keys.openpgp.org — <https://keys.openpgp.org/>
- WKD draft — <https://datatracker.ietf.org/doc/html/draft-koch-openpgp-webkey-service>
- rPGP — <https://github.com/rpgp/rpgp>
