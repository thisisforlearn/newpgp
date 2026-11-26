# NewPGP on Android — easy start (v0.1.3)

This guide is for an Android phone. Go back to [Start here](../GETTING_STARTED.md) any time.

> **Read this first:** the v0.1.3 Android apps are **previews of the screens only**. The part that locks and unlocks messages (we call it the "private engine") is **not connected yet**. When you try to make a key or encrypt a message, you will see a message such as "native PGP bridge not connected". **No pretend encryption is shown.** For real private mail today, use a computer: [Windows](START_WINDOWS.md), [Mac](START_MACOS.md), or [Linux](START_LINUX.md).

---

## 1. Download the app files

1. On your phone, open https://github.com/thisisforlearn/newpgp/releases/tag/v0.1.3
2. Download `newpgp-android-app-0.1.3.apk`. This is the main app. An **APK** is an Android app file, like an app from the Play Store but from the project website.
3. Optional: also download `newpgp-keyboard-0.1.3.apk`. This is a keyboard you can use in other apps.

> You may also see `newpgp-android-app-debug-app-debug.apk` and `newpgp-keyboard-debug-app-debug.apk`. Those are test builds. You do not need them.

---

## 2. Let your phone install app files

1. Open **Settings**.
2. Tap **Security** (on some phones it is under **Privacy** or **Apps**).
3. Tap **Install unknown apps**.
4. Tap the app you will open the download with — usually **Chrome**, **Files**, or **My Files**.
5. Turn on **Allow from this source**.

**You will see:** a switch that is now on. "Unknown source" only means the app did not come from the Play Store. It does not mean a virus. Only do this for the file from the official page above.

---

## 3. Install the app

1. Open your **Downloads** folder.
2. Tap `newpgp-android-app-0.1.3.apk`.
3. Tap **Install**.
4. Tap **Open**.

**You will see:** the NewPGP app open with a welcome screen.

---

## 4. Optional: turn on the NewPGP keyboard

1. Install `newpgp-keyboard-0.1.3.apk` the same way as above.
2. Open **Settings** → **System** → **Languages & input** → **On-screen keyboard**.
   - The words vary a little between phone brands. Look for **Keyboard**, **Manage keyboards**, or **Change keyboard**.
3. Turn on **NewPGP Keyboard**.
4. When you tap a text box, choose **NewPGP Keyboard** as your keyboard.

**You will see:** the NewPGP keyboard with a shield key. In later versions, tapping the shield key will lock your text. In v0.1.3 it shows the "private engine not connected" message.

---

## 5. First run: "Create my key"

1. Open the NewPGP app.
2. Tap **Get started**.
3. On the **Make your key** screen, type your name and your email address.
4. Tap **Create my key**.

**You will see:** a message saying key creation will be available when the private engine is connected, or "native PGP bridge not connected". This is expected in v0.1.3. The app has not made a fake key.

**To actually make a key today:** use the desktop app. It has the real **Create my key** button: [Windows](START_WINDOWS.md), [Mac](START_MACOS.md), [Linux](START_LINUX.md).

---

## 6. Send a first private message

**In v0.1.3 you cannot do this on the phone.** The app has a **Write** tab with a **Prepare message** button, but it stops with the "private engine not connected" message. Nothing is sent.

**To send one today:**

1. Make your key and prepare the message on a computer using the guides above.
2. Tap **Copy to clipboard** there.
3. Paste it into Gmail or Outlook on your phone and press **Send**.

---

## 7. Read a private message

**In v0.1.3 you cannot decrypt on the phone.** The app has a **Read** tab with a **Decrypt** button, but it also stops with the "private engine not connected" message.

**To read one today:** copy the whole locked block, from `-----BEGIN PGP MESSAGE-----` to `-----END PGP MESSAGE-----`, and paste it into the NewPGP desktop app's **Read** tab, then tap **Decrypt** there.

---

## If something goes wrong

| What you see | What to do |
| --- | --- |
| "App not installed" | Go to **Settings** → **Security** → **Install unknown apps** → your browser or Files → turn on **Allow from this source**, then tap the APK again. |
| The NewPGP keyboard is not in the list | Open **Settings** → **System** → **Languages & input** → **On-screen keyboard** → **Manage keyboards** and switch **NewPGP Keyboard** on. On Samsung, look under **General management**. |
| "native PGP bridge not connected" | This is normal in v0.1.3, not a mistake you made. Use the Windows, Mac, or Linux guide for real private mail. |

---

## Need help?

Email **nekomeowpurr@proton.me**. Or open a report at https://github.com/thisisforlearn/newpgp/issues and tell us what you saw.

**Coming soon:** the private engine for Android, plus more platforms. Watch the official page for links.

---

## About NewPGP

- Official project: https://github.com/thisisforlearn/newpgp
- This version: v0.1.3 — https://github.com/thisisforlearn/newpgp/releases/tag/v0.1.3
- Author: **Vaibhav** ([@thisisforlearn](https://github.com/thisisforlearn))
- Contributor: [@sudo-linux-neko](https://github.com/sudo-linux-neko)
- Licence: **GPL-3.0-or-later** (free software)
- Contact: **nekomeowpurr@proton.me**
