# Your NewPGP key, in plain words

A five-minute guide to making it, protecting it, sharing it, and knowing what to do when something looks wrong.

> **Print this page.** Keep the paper somewhere safe. Never write your password or your 36-digit setup code on it, and never leave the paper next to your computer or your backup file.

## What your key is

Think of your key as a **padlock and a key ring**. Your **public key** is an open padlock you can hand to anyone: they snap it shut to lock a message for you. Your **private key** is the one key that opens it, and it stays on your device like a ring only you hold. You never need to send your private key anywhere, and NewPGP will never ask you for it.

## Make it (2 minutes)

**On a computer, use the guided setup.** Type this one line and press Enter:

```
newpgp setup
```

The guide asks one question at a time: your name, your email address, and whether you want a password. It then makes your key and reminds you to save a backup. Follow the questions in order — there is nothing else to learn first.

**Or, in the desktop app:**

1. Open NewPGP and go to **Keys & people**.
2. Open **Create a new key**.
3. Type your name.
4. Type your email address.
5. Choose a password (see the next section), or leave the box empty.
6. Click **Create my key**.
7. Write down what the app shows you, then make a backup (section 4).

## Choose a password

One rule: **a long phrase only you know**. Four random words are a good start. Longer is better. You do **not** need capital letters, numbers, or odd symbols.

- **Make it new.** Use this phrase for your NewPGP key and nothing else.
- **Write it down and store it safely.** There is no "forgot password" button for a private key. If you forget it, your encrypted mail cannot be recovered — not by us, not by anyone.
- **Leaving it empty is allowed.** The app will make an unlocked key. That is fine for learning. But then anyone who gets your device or your backup file can read your mail, so protect the backup file extra carefully: keep it offline and never send it through email or chat.

This is what public guidance recommends: NIST says length matters, not strange character rules; EFF recommends long phrases made of random words. See the sources at the end.

## Back it up safely

A backup is a copy of your private key in a file. Do this once, right after you make your key.

- [ ] Let NewPGP create the backup for you during setup, or use the backup step in the app.
- [ ] NewPGP gives you a random **36-digit setup code**, written as nine groups of four digits. Let the app choose it. Do not make it up yourself.
- [ ] **Write the code down** and keep it somewhere safe. It cannot be recovered if lost.
- [ ] **Never send the code with the backup file.** If the file goes by email, the code must go another way: read it aloud on the phone, send a text, or scan a QR code in person.
- [ ] **Store the backup offline** — for example, on a USB drive kept in a safe place. A backup that is always online is always exposed.
- [ ] If you are handing a backup to someone else, the safest method is to **encrypt the file to their already-known public key**. Then no code has to travel at all. Full details in [KEY_TRANSFER.md](KEY_TRANSFER.md).

| If you see… | Do this… |
| --- | --- |
| A backup file with its code in the same email | Delete both. Ask for a fresh code by phone. |
| A "setup code" that is not 36 digits | Stop. It is not the NewPGP code. |
| A lost setup code | Make a new backup while you still have your key. The old backup cannot be opened. |
| Your private key is gone and there is no backup | Stop. Old encrypted mail cannot be recovered. |

## Share your public key

Your **public key** is safe to give to anyone. It can lock a message, but it cannot open one. Sharing it does not give away your password or your private key.

- Usually you do nothing. NewPGP looks up keys automatically:
  - **WKD** — a friend's email provider may publish their key at a standard web address.
  - **keys.openpgp.org** — a public directory that publishes a key only after its email address is verified.
  - **Autocrypt** — ordinary email messages quietly carry keys, so NewPGP can learn them.
- For someone with no published key, send an **invite link**. It carries a short safety number so they can tell the link really came from you.
- For extra confidence, **call the person and compare the safety number** you both see. Use a phone number you already trust, not the email you are worried about.

## If something changes

The first time NewPGP sees a key, it trusts it. If that key later changes, NewPGP warns you and **keeps the old key**. It will not switch over until you say so.

| If you see… | Do this… |
| --- | --- |
| "The key for (name) has changed" | Pause. Do not send private mail to that address yet. |
| An old and a new safety number on screen | Call the person on a number you already trust. Ask them to read their safety number aloud. |
| The two numbers match | Accept the change (confirm it). |
| The numbers do not match, or you cannot reach them | Reject the change. Keep the old key. Ask for help below. |
| A message that demands you act now or click a link | Ignore it. Scams rely on hurry. Call the person instead. |

Never compare a safety number inside the same email that worried you. If that email was fake, the number in it can be fake too.

## What NewPGP cannot do

- **It does not hide who, when, or the subject.** Mail servers still see sender, recipient, subject line, and time. Keep secrets out of the subject line. See [THREAT_MODEL.md](../THREAT_MODEL.md).
- **It has no forward secrecy yet.** If someone steals your private key later, they may be able to read old messages they saved. This is planned for a later version; it is not in this one.
- **Version 0.1 has not been audited.** It is new software — good for learning and everyday privacy, not for life-or-death secrecy. See [SECURITY_AUDIT.md](SECURITY_AUDIT.md) and [SECURITY.md](../SECURITY.md).
- **The Android APKs are UI previews.** The screens are there, but the real encryption engine is not connected yet. Do not rely on them for private mail.
- **It cannot undo a sent message or recover a lost key.** Once mail has arrived, it has arrived. Without a backup, a lost key means lost mail.

## Get help

First, on a computer, run this and keep the report it prints:

```
newpgp doctor
```

Then contact us:

- **Email:** nekomeowpurr@proton.me
- **Bug reports and questions:** <https://github.com/thisisforlearn/newpgp/issues>
- **Official project and download links:** <https://github.com/thisisforlearn/newpgp>

NewPGP is written by **Vaibhav** ([thisisforlearn](https://github.com/thisisforlearn)), with contributor **[@sudo-linux-neko](https://github.com/sudo-linux-neko)**. It is free software under [GPL-3.0-or-later](../LICENSE) — see [NOTICE.md](../NOTICE.md) for why.

> **Please never send** your password, setup code, private key, or real message contents in an email or a public issue. We cannot help you if those leak, and no one needs them to help you.

## Sources (for the curious)

- [RFC 9580, section 3.7](https://www.rfc-editor.org/rfc/rfc9580.html) — how a password locks a private key, and why Argon2id is recommended.
- [Autocrypt Level 1](https://docs.autocrypt.org/level1.html) — keys carried in email, prefer-encrypt, and the 36-digit (numeric9x4) setup code pattern.
- [OpenPGP Web Key Directory draft](https://datatracker.ietf.org/doc/html/draft-koch-openpgp-webkey-service) — finding keys over a secure website.
- [keys.openpgp.org](https://keys.openpgp.org/about) — publishing keys only after the email address is verified.
- [NIST SP 800-63B](https://pages.nist.gov/800-63-4/sp800-63b.html) — long passwords, no arbitrary character rules.
- [EFF Dice-Generated Passphrases](https://www.eff.org/dice) — long phrases made of random words, used for only one purpose.
- Our own guides: [KEY_TRANSFER.md](KEY_TRANSFER.md), [SECURITY_AUDIT.md](SECURITY_AUDIT.md), [FAQ.md](FAQ.md).
