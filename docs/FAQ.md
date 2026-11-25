# NewPGP FAQ

Plain-language answers to common questions.

## Basics

### What is NewPGP?

A friendly set of tools for sending private email using the OpenPGP standard (RFC 9580). It includes a built-in mail app, a browser extension for Gmail/Outlook, mobile apps, and an Android keyboard. It is not a new encryption protocol — it speaks standard PGP.

### Who is behind NewPGP?

NewPGP is led by **Vaibhav** (GitHub: [thisisforlearn](https://github.com/thisisforlearn)), the author and primary maintainer, with **sudo-linux-neko** ([@sudo-linux-neko](https://github.com/sudo-linux-neko)) as a contributor. See [AUTHORS.md](../AUTHORS.md) and [CONTRIBUTORS.md](../CONTRIBUTORS.md). The official project page is <https://github.com/thisisforlearn/newpgp>; more platforms are coming soon.

### Do I need to understand cryptography?

No. You create a key pair once (the app walks you through it), pick a good password, and write email normally. NewPGP handles the rest. If you want the details, see [ARCHITECTURE.md](../ARCHITECTURE.md).

### What is a "key" in plain language?

You have a **private key** (a secret file locked with your password, kept on your device) and a **public key** (a file you can give to anyone). Others lock messages with your public key; only your private key unlocks them. You sign with your private key; others verify with your public key. You never send your private key to anyone.

### Is this the same PGP as other tools?

Yes. NewPGP uses OpenPGP RFC 9580 via rPGP, so it works with other OpenPGP implementations. It uses v4/v6 keys with Ed25519 signing and Curve25519 encryption subkeys, and AEAD where available.

---

## Passwords and keys

### My password "encrypts with SHA-512 then SHA-256" — is that right?

Not quite, and that is okay — it is a common mix-up. **SHA-512 and SHA-256 are hash functions; they do not encrypt.** The correct construction has two steps:

1. **Derive** a strong key from your password using a key-derivation function — **Argon2id** (preferred, memory-hard) or iterated+salted S2K.
2. **Encrypt** your key with a real cipher (**AES**) using that derived key.

So: *Argon2id derives, AES encrypts.* Hashes may be part of the derivation process but never encrypt on their own. NewPGP follows this construction exactly.

### How do I move a key or password to someone without it being intercepted, and without meeting in person?

You cannot send a secret safely over an insecure channel unless you already share a secret or have a trusted channel. The best answer is usually **not to send a password at all**: encrypt the backup to the recipient's already-known public key. Otherwise send the encrypted file one way and a random 36-digit setup code (numeric9x4, ~119 bits) out-of-band by voice/SMS/QR. Full details in [KEY_TRANSFER.md](KEY_TRANSFER.md).

### What if I lose my private key?

If you have no backup, encrypted messages cannot be recovered — that is the nature of strong encryption. Keep a safe offline backup made with the guidance in [KEY_TRANSFER.md](KEY_TRANSFER.md).

---

## Finding friends' keys

### How does NewPGP find my friends' keys?

Automatically, in this order: **WKD** (advanced method first, then direct), **keys.openpgp.org** (verified emails only), **Autocrypt** headers in email, and finally **invite links** for people with no published key.

### What does NewPGP do with weird WKD responses?

WKD follows strict rules: it uses a SHA-1 + Z-Base-32 hash of the local part for lookup, tries the advanced method before the direct method, **drops extra User-IDs** (only the requested identity is kept), and **never accepts an HTTP 401** as if it were a valid key response.

### Won't a public key server leak who my friends are?

NewPGP uses keys.openpgp.org, which only publishes keys with **verified email addresses** and **strips third-party signatures**. That means looking up a key there does not expose your social graph.

### Do I need key signing parties / the Web of Trust?

No. NewPGP uses **Trust On First Use (TOFU)** and short **safety numbers** you can compare with a friend if you want extra confidence. Key signing parties create a lot of friction for non-technical people, so we deliberately do not depend on them.

---

## Sending email

### What happens by default in the browser extension?

By default it sends the **normal plaintext email and appends the PGP armored block**. Your non-PGP friends can still read the message, and PGP users gradually get familiar with the format. There is a toggle to switch to **PGP-only**.

### Will NewPGP delete or overwrite what I wrote?

No. By default it **appends** the encrypted block; it does not delete or replace your draft body. Your writing is safe.

### My friend's mail client mangled the encrypted block. Can it still be read?

Usually yes. Before parsing, NewPGP **normalizes** armored blocks: it strips `>` quoting markers, unwraps HTML, removes trailing spaces, and repairs common line-break damage.

### Can I send photos, videos, and files?

Yes. OpenPGP encrypts any bytes, so attachments work exactly like text. NewPGP preserves the **filename and MIME type** in the metadata so files remain usable after decryption.

### Can I "undo" a sent email?

Not really — and we will not pretend otherwise. Once an email has arrived, it cannot be pulled back. NewPGP offers a **local plaintext copy saved at send time** and a **short cancel-send window where the mail server supports it**. Treat it as a convenience, not a guarantee.

### Can I search my encrypted mail?

Yes, but only **locally**. NewPGP builds a **local encrypted index** and searches decrypted content on your device. Nothing is sent to a server for search.

### Why are my drafts encrypted to myself?

So your email provider cannot autosave a plaintext copy of what you are writing. The draft is encrypted with your own public key and decrypted locally when you open it again.

---

## Mobile and keyboard

### How does the Android keyboard work?

It is a Kotlin IME with strong autocorrect and autocapitalization. A dedicated **"encrypt" key** encrypts the current composing text on tap.

### Does the keyboard automatically encrypt everything I type?

Only if you explicitly turn on **auto-append mode**, which is **off by default**. When it is on, there is a **visible indicator and warning**. This is deliberate: an IME that silently captured all keystrokes would be a serious privacy risk, and an IME cannot see "send" events, so it cannot know when a message is finished. The encrypt-on-tap key is the safe, explicit path.

---

## Security and limitations

### Is NewPGP audited?

No. v0.1 has **not** been independently audited. Even mature tools have bugs (a 39C3 talk found 14 across GnuPG, Sequoia, age, and minisign), which is why we use no custom crypto, keep code minimal, and test/fuzz heavily. Please do not rely on v0.1 for life-or-death secrecy. See [SECURITY.md](../SECURITY.md).

### Does NewPGP hide who I am emailing or the subject?

No. SMTP still exposes sender, recipient, subject, and timestamp. OpenPGP protects message contents, not the envelope. Do not put sensitive information in the subject line.

### Does NewPGP have forward secrecy?

Not in v0.1. Classic OpenPGP does not provide it: if your private key is stolen later, saved old messages could be read. A hybrid **Signal Double/Triple Ratchet** design is a Phase 3 item (see [ROADMAP.md](../ROADMAP.md)) and is out of scope for v0.1. We do not claim security we do not have.

### "Nobody I know uses PGP." Will this even work?

Yes — that is exactly why the default is to **send both** the normal message and the encrypted block, and to use **Autocrypt opportunistic encryption**. You do not force anyone to install anything; they can still read your mail, and encryption becomes available naturally as more of your contacts adopt it. See [ISSUES_ADDRESSED.md](ISSUES_ADDRESSED.md).

---

## References

- Official project — <https://github.com/thisisforlearn/newpgp>
- Authors and contributors — [AUTHORS.md](../AUTHORS.md), [CONTRIBUTORS.md](../CONTRIBUTORS.md)
- RFC 9580 — <https://www.rfc-editor.org/rfc/rfc9580.html>
- WKD draft — <https://datatracker.ietf.org/doc/html/draft-koch-openpgp-webkey-service>
- keys.openpgp.org — <https://keys.openpgp.org/>
- Autocrypt Level 1 — <https://docs.autocrypt.org/level1.html>
- rPGP — <https://github.com/rpgp/rpgp>
- age — <https://github.com/FiloSottile/age>
- Sequoia PGP — <https://sequoia-pgp.org/>
- Signal Double Ratchet — <https://signal.org/docs/specifications/doubleratchet/>
