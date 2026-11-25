# NewPGP

**Friendly, fast, cross-platform OpenPGP for the rest of us.**

NewPGP is an easy-to-use email encryption suite for people who are not security experts. It uses the OpenPGP standard (RFC 9580) behind a calm, simple interface, so you can send private email to your friends — even the ones who have never installed encryption software.

> **Plain-language warning, up front:** NewPGP v0.1 is new software. It has **not been independently audited**. It is great for learning, everyday privacy, and getting more people encrypted. Please **do not rely on it for life-or-death secrecy**. See [Security](#security-and-privacy-honestly).

**Author:** Vaibhav (legal name) — <https://github.com/thisisforlearn>
**Official project:** <https://github.com/thisisforlearn/newpgp>
**License:** GNU GPL version 3 or later (GPL-3.0-or-later) — see [LICENSE](LICENSE) and [NOTICE.md](NOTICE.md). The reason for choosing v3 is explained in [NOTICE.md](NOTICE.md).

> **More platforms are coming soon.** NewPGP will be available on additional platforms over time; setting each one up takes time, so please check this page for the official links.

---

## What is NewPGP?

NewPGP is a set of tools that work together:

- A **built-in email app** (IMAP/SMTP) that encrypts and decrypts as you type.
- A **browser extension** for Gmail and Outlook, so you can keep the webmail you already use.
- **Mobile apps** and an **Android keyboard** that lets you encrypt text in any app.

Under the hood, everything is built on **rPGP**, a well-known, pure-Rust OpenPGP library. NewPGP does **not** invent its own cryptography. It speaks the same OpenPGP language that other PGP tools speak.

### What it is NOT

- It is **not a new encryption protocol**. It uses the standard OpenPGP format (RFC 9580).
- It does **not** hide who you are emailing, the subject line, or when you sent it (see [Metadata](#what-it-does-not-do)).
- It does **not** provide "forward secrecy" yet — if a key is stolen later, old messages could be read. This is a known limitation, planned for a later phase.
- It does **not** make the internet anonymous. It makes the *contents* of email private.

---

## A plain-language explanation of keys

If you are new to this, "public-key encryption" can sound intimidating. Here is the whole idea in a few sentences:

- You have a **private key** — a secret file that stays on your device, locked with a password. Think of it like a key ring only you hold.
- You also have a **public key** — a file you can freely give to anyone. Think of it like an open padlock that anyone can snap shut, but only your private key can open.
- To send you a private message, someone locks it with your **public key** (your open padlock). Once locked, **only your private key can unlock it** — not even the sender can.
- To prove a message really came from you, you "sign" it with your **private key**. Others check that signature with your **public key**.

That is it. You never have to send your secret anywhere. NewPGP handles the locking, unlocking, and signing for you.

**Your password** only unlocks your private key *on your own device*. It is not the password used to encrypt messages to other people.

---

## Features

- 🔐 **Standard OpenPGP (RFC 9580)** — works with other PGP tools.
- 🔑 **Modern keys** — v4/v6 keys with Ed25519 signing and Curve25519 encryption subkeys, and AEAD where available.
- 🧭 **Automatic key finding** — finds friends' public keys through WKD, keys.openpgp.org, and Autocrypt, with invite links for everyone else.
- ✉️ **Built-in mail client** — drafts are encrypted to yourself, so webmail providers cannot autosave a plaintext copy.
- 🧩 **Browser extension** — for Gmail and Outlook, with a friendly default that keeps non-PGP friends in the loop.
- 🔎 **Private search** — search your decrypted messages locally. Nothing is sent to a server.
- 📎 **Any file** — photos, videos, and attachments are encrypted just like text.
- ⌨️ **Android keyboard** — encrypt the text you are typing, right where you type it.

---

## What works today (v0.1 alpha)

Being honest about the current state matters more than sounding finished:

- ✅ **Rust core** — key generation, text/file encryption, decryption, signatures, tolerant armor repair, Argon2id backups, WKD + keys.openpgp.org discovery, Autocrypt headers, TOFU safety numbers, encrypted local search.
- ✅ **Command line + native bridge** — `newpgp init`, `key`, `encrypt`, `decrypt`, `compose`, `backup`, `search`, and `bridge` for the browser extension.
- ✅ **Browser extension** — buildable MV3 extension with 51 passing tests and send-both by default.
- ✅ **Desktop shell** — a Tauri app using the same Rust crates (builds in CI on Windows/macOS/Linux).
- 🚧 **Android keyboard** — full Kotlin IME source and mock tests; the Rust bridge and signed APK are wired in CI but need a device to verify.
- 🚧 **iOS** — SwiftUI shell with a typed bridge contract; real engine wiring is Phase 2.
- 🚧 **Built-in IMAP/SMTP sync** — composition and encrypted drafts are implemented; the mail transport UI is still to be connected.

### Try the CLI right now

```bash
newpgp init --name "Your Name" --email you@example.com
newpgp key find friend@example.com          # WKD / keys.openpgp.org
newpgp compose --body "Hello" --to friend@example.com
newpgp key invite friend@example.com         # link to share with someone without a key
newpgp backup create backup.asc              # writes a 36-digit setup code
```

---

## 60-second quick start

1. **Install NewPGP** (see [Downloads](#downloads)) on your computer or phone.
2. **Create your keys.** Open the app and choose *Create my keys*. Pick a strong password — this locks your private key on your device.
3. **Add your email account** (built-in mail app) or enable the browser extension for Gmail/Outlook.
4. **Write to a friend.** Just write a normal email. NewPGP looks for their public key automatically.
   - If they use PGP, the message is encrypted for them.
   - If they don't, NewPGP still sends the normal message **plus** an encrypted block, so nothing breaks and they slowly get used to it.
5. **That's it.** When they reply encrypted, NewPGP decrypts it for you automatically.

> No key signing parties, no complicated setup. NewPGP trusts keys the first time it sees them and shows you a short **safety number** you can compare with a friend if you want extra confidence.

### Screenshots

> *Placeholder — screenshots will be added before release.*

| Create your keys | Compose an encrypted email | Compare a safety number |
| --- | --- | --- |
| *(image placeholder)* | *(image placeholder)* | *(image placeholder)* |

---

## Downloads

> *Placeholder — official download links will be published here for Windows, macOS, Linux, Android, and the browser extension store.*

| Platform | Status |
| --- | --- |
| Windows | Coming soon |
| macOS | Coming soon |
| Linux | Coming soon |
| Android (app + keyboard) | Coming soon |
| Browser extension (Chrome/Firefox/Edge) | Coming soon |

Prefer to build from source? See [ARCHITECTURE.md](ARCHITECTURE.md) for the component layout and the CLI quick start above.

## Sending changes or feedback

Changes can be submitted by **email**, **GitHub**, or any other platform where this code is published — the official links for every platform are listed on the project page: <https://github.com/thisisforlearn/newpgp>.

- GitHub issues and pull requests: <https://github.com/thisisforlearn/newpgp>
- Email (code, patches, ideas, bug reports): **nekomeowpurr@proton.me**
- See [CONTRIBUTING.md](CONTRIBUTING.md) for the full instructions and licensing note.

---

## People / credits

NewPGP is led by **Vaibhav** (GitHub: [thisisforlearn](https://github.com/thisisforlearn)), the author and primary maintainer, with **sudo-linux-neko** ([@sudo-linux-neko](https://github.com/sudo-linux-neko)) as a contributor.

- [AUTHORS.md](AUTHORS.md) — who wrote the project and what each person does.
- [CONTRIBUTORS.md](CONTRIBUTORS.md) — everyone who has helped, and how to reach them.

Want to be listed too? See [Contributing](#contributing) and [CONTRIBUTING.md](CONTRIBUTING.md).

---

## How NewPGP finds your friends' keys

You should not have to email people and beg them for a key file. NewPGP looks for public keys automatically, in this order:

1. **WKD** — many email providers publish keys at a standard web address.
2. **keys.openpgp.org** — a public directory that only lists keys with *verified email addresses*, so it does not leak your social graph.
3. **Autocrypt** — headers in the emails you already exchange let NewPGP learn keys and encrypt opportunistically.
4. **Invite links** — for people with no published key, share a link containing a fingerprint/safety number.

This is all explained in more detail in [ARCHITECTURE.md](ARCHITECTURE.md) and [FAQ.md](docs/FAQ.md).

---

## Security and privacy, honestly

We think it is important to tell you what encryption does **not** do:

- **Metadata is still visible.** SMTP reveals who you wrote to, who you are, the subject line, and the timestamp. OpenPGP does not hide these. NewPGP cannot fix that alone.
- **No forward secrecy (yet).** Classic OpenPGP does not have it. If your private key is stolen later, an attacker who saved old messages could read them. A hybrid forward-secret design is planned for Phase 3, and is explicitly **out of scope for v0.1**.
- **Mature tools have bugs too.** A talk at 39C3 found 14 bugs across well-known tools (GnuPG, Sequoia, age, minisign). That is why NewPGP uses no custom crypto, keeps the code small, and leans on heavy tests and fuzzing. It is still **not audited** — please see [SECURITY.md](SECURITY.md).
- **Backups matter.** If you lose your private key, encrypted mail is unrecoverable. See [KEY_TRANSFER.md](docs/KEY_TRANSFER.md) for safe backup and transfer options.

For the full picture, see [THREAT_MODEL.md](THREAT_MODEL.md).

---

## Documentation

- [ARCHITECTURE.md](ARCHITECTURE.md) — how the pieces fit together.
- [THREAT_MODEL.md](THREAT_MODEL.md) — what is protected and what is not.
- [SECURITY.md](SECURITY.md) — security policy and responsible disclosure.
- [ROADMAP.md](ROADMAP.md) — where the project is going.
- [KEY_TRANSFER.md](docs/KEY_TRANSFER.md) — how to move keys/passwords safely.
- [FAQ.md](docs/FAQ.md) — common questions, answered plainly.
- [ISSUES_ADDRESSED.md](docs/ISSUES_ADDRESSED.md) — every problem we set out to solve, and its honest status.
- [BUILD_ARTIFACTS.md](docs/BUILD_ARTIFACTS.md) — how to get the APK and desktop installer from GitHub Actions.

---

## Contributing

NewPGP is open source and welcomes help — especially testing, documentation, and accessibility feedback. Please read [SECURITY.md](SECURITY.md) before reporting a security issue (do not open a public issue for vulnerabilities). General bug reports and feature requests are welcome in the issue tracker. *(Issue tracker link placeholder.)*

## License

NewPGP is licensed under **GNU GPL version 3 or later (GPL-3.0-or-later)** — see [LICENSE](LICENSE) and [NOTICE.md](NOTICE.md), which explains why v3 was chosen.

---

## References

- OpenPGP standard (RFC 9580) — <https://www.rfc-editor.org/rfc/rfc9580.html>
- Web Key Directory (WKD) draft — <https://datatracker.ietf.org/doc/html/draft-koch-openpgp-webkey-service>
- keys.openpgp.org — <https://keys.openpgp.org/>
- Autocrypt Level 1 — <https://docs.autocrypt.org/level1.html>
- rPGP (the `pgp` Rust crate) — <https://github.com/rpgp/rpgp>
- age — <https://github.com/FiloSottile/age>
- Sequoia PGP — <https://sequoia-pgp.org/>
- Signal Double Ratchet — <https://signal.org/docs/specifications/doubleratchet/>
