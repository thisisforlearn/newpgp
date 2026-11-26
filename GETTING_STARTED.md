# Start here — NewPGP v0.1.3

NewPGP helps you send private email. You keep using the email you already use. NewPGP adds the private copy for you.

This page is the short way in. Pick your device and follow the steps. It should take about **five minutes**.

---

## The 5-minute path

**Step 1 — Install NewPGP.**

- Windows → [Windows setup](docs/START_WINDOWS.md)
- Mac → [Mac setup](docs/START_MACOS.md)
- Linux → [Linux setup](docs/START_LINUX.md)
- Android phone → [Android setup](docs/START_ANDROID.md)
- Browser add-on → [Browser setup](docs/START_BROWSER.md)

**Step 2 — Make your key.** Open the app you just installed. A welcome card appears. Type your name, your email address, and a password you will remember (or leave it empty). Click the big button labelled **Create my key**. Click **Next: write a message**, then **Start writing**. This happens only once.

**You will see:** a short code made of letters and numbers. That is your key. Leave it on your device.

**Step 3 — Write normally.** Type your message like you always do. NewPGP adds a private copy underneath. Click **Prepare message**, then **Copy to clipboard**, then paste into Gmail, Outlook, or your usual email app. Press **Send** like normal.

**You will see:** a message that begins with `-----BEGIN PGP MESSAGE-----`. That is the locked copy.

---

## What is a key?

A key is a secret code saved on your device that locks and unlocks your private mail. Part of it is a **public key** you may give to anyone, and it works like an open padlock that anyone can snap shut. Only your secret part can open that padlock, so nobody else can read the mail that was locked for you.

---

## The whole idea in 3 steps

1. **Install the app** on your device.
2. **Make your key** once. Pick a password you will remember.
3. **Write normally.** NewPGP adds the private copy. You do not have to learn a new way to write email.

---

## Please know this before you trust it

> **Not checked by outside experts.** NewPGP v0.1.3 has **not** had an independent security audit. It is good for learning and everyday privacy. Please **do not** rely on it for life-or-death secrecy.

> **Android is a preview only.** The v0.1.3 Android app and keyboard show the screens, but the private-lock part is **not connected yet**. If you try to make a key there, you will see "native PGP bridge not connected". Use a computer for real private mail for now. See [Android setup](docs/START_ANDROID.md).

> **Send both means ordinary people can still read your mail.** By default your message stays as normal text **plus** a locked copy. This is on purpose: friends who do not use NewPGP can still read it. If you want **only** the locked copy, choose **Encrypted only** or turn on **Send PGP only**. Then people without PGP cannot read it.

> **The email address, subject, and time are still visible.** NewPGP hides the message words, not who you wrote to.

---

## Which guide do I use?

| I have... | Start here |
| --- | --- |
| A Windows computer | [Windows setup](docs/START_WINDOWS.md) |
| A Mac | [Mac setup](docs/START_MACOS.md) |
| A Linux computer | [Linux setup](docs/START_LINUX.md) |
| An Android phone | [Android setup](docs/START_ANDROID.md) |
| Gmail or Outlook in a browser | [Browser setup](docs/START_BROWSER.md) |

**Coming soon:** more platforms are being added. Check the official project page for new links.

---

## Need help?

Email **nekomeowpurr@proton.me**. Or open a report at https://github.com/thisisforlearn/newpgp/issues and describe what you saw.

---

## About NewPGP

- Official project: https://github.com/thisisforlearn/newpgp
- This version: v0.1.3 — https://github.com/thisisforlearn/newpgp/releases/tag/v0.1.3
- Author: **Vaibhav** ([@thisisforlearn](https://github.com/thisisforlearn))
- Contributor: [@sudo-linux-neko](https://github.com/sudo-linux-neko)
- Licence: **GPL-3.0-or-later** (free software)
- Contact: **nekomeowpurr@proton.me**
