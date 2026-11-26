# Start: NewPGP Android keyboard (5-minute guide)

This gets you from “I downloaded an APK” to “I can type, encrypt, and polish text”
on an Android phone. No developer knowledge needed.

> **Note about names:** the exact words on the buttons are decided by the UI team
> and may differ slightly from this guide. Look for the **shield** (encryption)
> and **sparkle / ✨** (AI rewrite) icons; that is what matters.

## Before you start

- An Android phone running Android 7.0 (API 24) or newer.
- The keyboard APK for **v0.1.4** from the
  [GitHub Releases page](https://github.com/thisisforlearn/newpgp/releases)
  (file name ends in `.apk`, e.g. `newpgp-keyboard-debug.apk` or the signed
  `app-release.apk`). v0.1.3 and earlier do **not** have real crypto or the AI
  model.
- About five minutes and permission to install an app from outside the Play
  Store.

## Step 1 — Install the APK (about 1 minute)

1. On your phone, open the browser or Files app and tap the downloaded `.apk`.
2. Android will ask permission to install from that app. Enable **“Install
   unknown apps”** for your browser/files app when prompted — this is normal for
   APK installs.
3. Tap **Install**. A debug-signed APK may show an extra warning (“built with a
   debug certificate”); that is expected for CI builds. Only install builds you
   trust.
4. When it finishes, do **not** tap “Open” yet — an input method has to be
   enabled in Settings first.

## Step 2 — Enable it in Android Settings (about 2 minutes)

The exact menu names vary by phone brand. The common path is:

**Settings → System → Languages & input → On-screen keyboard → Manage keyboards**
→ switch **NewPGP Keyboard** **on**.

If you cannot find that path, use the universal fallback: **open Settings and
search for “keyboard”**, then look for **Manage keyboards**, **Current keyboard**,
or **On-screen keyboard**.

Then choose it as your active keyboard:

- On the keyboard itself, tap the **keyboard-switcher icon** (usually near the
  space bar / bottom row) and pick **NewPGP Keyboard**; or
- go back to **Languages & input → Current keyboard** and select it there.

Open any text field (for example a notes app) and type a few words. If NewPGP
keys appear, you are done with setup.

## Step 3 — Tap the shield to really encrypt (about 1 minute)

The **shield button** is the security feature. In v0.1.4 it uses real OpenPGP
encryption implemented in Rust and running on your device.

1. Type a message in the app you are using.
2. Set up a recipient key first. Open the **NewPGP companion app**, add/select a
   contact's public key, then come back to your text field. (Without a recipient
   key the shield will tell you encryption is unavailable — it will not invent
   fake ciphertext.)
3. Tap the **shield**. The current word — or the whole field if you are not
   composing a word — is replaced with an `-----BEGIN PGP MESSAGE-----` armored
   block.
4. Read the status message. A failure is shown visibly; nothing is silently
   faked.

Important: encryption happens **when you tap the shield**, not automatically.
The plaintext may already be saved in the other app's draft/autosave before you
encrypt, and NewPGP cannot undo that. Do not rely on automatic encryption.

## Step 4 — Tap the sparkle to rewrite tone (about 1 minute)

The **sparkle / ✨ button** is the offline AI writing helper.

1. Type a sentence.
2. Tap the **sparkle**. The keyboard suggests a cleaner version (grammar and
   wording).
3. Accept the rewrite if you like it, or keep your original text.

The model runs **entirely on your phone** — no text is sent to the internet, and
the keyboard has no network permission. It is a small grammar-correction model,
so always read the result; it can get names or technical terms wrong. It does
**not** encrypt anything. See [KEYBOARD_AI.md](KEYBOARD_AI.md) for details and
honest limitations.

## Troubleshooting

| Symptom | What to try |
| ------- | ----------- |
| Keyboard not in the list | Reinstall the APK, then repeat Step 2; search Settings for “keyboard”. |
| Keyboard enabled but not showing | Tap the keyboard-switcher icon while a text field is focused, or set it as Current keyboard in Settings. |
| Shield says “encryption unavailable” | Open the companion app and add/select a recipient public key first. |
| Sparkle slow on first tap | The on-device model is loading into memory; later taps are faster. |
| Sparkle gives odd wording | It is a small model — edit or ignore the suggestion. |

## Privacy in one paragraph

No keystroke logging, no analytics, no network permission. Composing text lives
in memory for the current input session. Encryption and AI rewriting both run on
your device. The only app-private data stored is a couple of settings and learned
single-word counts; cloud backup and device transfer are disabled. Remember that
the app you are typing into may have its own sync/draft behavior that NewPGP
cannot control.
