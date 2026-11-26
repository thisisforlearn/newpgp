# NewPGP on a Mac — easy start (v0.1.3)

This guide is for a Mac computer. It should take about **five minutes**. Go back to [Start here](../GETTING_STARTED.md) any time.

> **Please know this first:** v0.1.3 has **not** been checked by outside security experts. Do not rely on it for life-or-death secrets.

> **Which Mac?** This release has an installer for **Apple silicon Macs** (M1, M2, M3, M4). There is no Intel Mac installer in v0.1.3. More platforms are coming soon.

---

## 1. Download the installer

1. Open the download page: https://github.com/thisisforlearn/newpgp/releases/tag/v0.1.3
2. Find the file named `NewPGP_0.1.3_aarch64.dmg`.
   - You may also see `newpgp-0.1.3-macos-aarch64.dmg`. Both names are in this release. Pick **one**.
3. Click it. Your Mac saves it in **Downloads**.

**You will see:** a disk-image file in Downloads. Mac calls this kind of file a **DMG** (a folder you open).

---

## 2. Install it

1. Open **Downloads** in Finder.
2. Double-click the file you downloaded.
3. Drag the **NewPGP** icon into the **Applications** folder shown in the window.
4. Close the window and push the eject button next to the disk image if you see one.
5. Open **Launchpad** and click **NewPGP**.

**You will see:** the NewPGP app open.

**If your Mac says "Apple cannot check it for malicious software":**

1. Click **Done**.
2. Open **System Settings** → **Privacy & Security**.
3. Scroll down and click **Open Anyway**.
4. Click **Open** in the box that appears.

This is the Mac's security check. It appears because this early build is not signed by Apple. Only do this for the file from the official page above.

---

## 3. Make your key ("Create my key")

1. When NewPGP opens for the first time, a welcome card appears. It says **Welcome to NewPGP** and **Step 1 of 3**.
2. In **Your name**, type your name.
3. In **Your email**, type your email address.
4. In **Password**, type a password you will remember. You may leave it empty; NewPGP then makes a backup code instead. If you use a password, write it down — there is **no way to recover it**.
5. Click the blue button labelled **Create my key**.

**You will see:** **Step 2 of 3** and a short code made of letters and numbers. That is your key. It stays on your Mac.

6. If you want a copy of your public key, click **Copy my public key**. It is safe to share.
7. Click **Next: write a message**, then **Start writing**.

**If the welcome card does not appear:** click the **Keys & people** tab, click **Create a new key**, fill in **Name**, **Email**, and **Password**, then click **Create my key**.

---

## 4. Send your first private message

1. Click the tab labelled **Write**.
2. In **Recipients**, type your friend's email address. (NewPGP needs their public key first. Ask them to send it to you, then add it under **Keys & people** → **Add key**.)
3. In **Your message**, type your message.
4. Leave **Normal + encrypted copy (recommended)** selected.
5. Click **Prepare message**.

**You will see:** "Message ready. Copy it into your email app." and the finished text in the **Ready to send** box.

6. Click **Copy to clipboard**.
7. Open Gmail, Outlook, or your usual email app. Paste the text into a new email and press **Send**.

**Want only the locked copy?** Choose **Encrypted only** before you click **Prepare message**. People who do not use PGP will then see unreadable text.

> **Note:** In v0.1.3 the NewPGP app does not send email by itself. It makes the message and you paste it into your email app. This is on purpose.

---

## 5. Read a private message

1. Open the email your friend sent.
2. Copy the whole locked block, from `-----BEGIN PGP MESSAGE-----` to `-----END PGP MESSAGE-----`.
3. In NewPGP, click the tab labelled **Read**.
4. Paste the whole block into the big box.
5. Click **Decrypt**.

**You will see:** a **Decrypted** heading with your friend's words, plus a **Signature:** line that says whether the message was verified.

---

## If something goes wrong

| What you see | What to do |
| --- | --- |
| "Apple cannot check it for malicious software" | Go to **System Settings** → **Privacy & Security** → **Open Anyway** → **Open**. Only do this for the file from the official page above. |
| I cannot find **Create my key** | On the welcome card it is the big blue button. If the card is gone, click **Keys & people**, then **Create a new key** to open that section. |
| "I need a key for that person" | Ask your friend for their public key. In NewPGP go to **Keys & people** → **Add key**, paste their key, click **Add key**, then try again. |

---

## Need help?

Email **nekomeowpurr@proton.me**. Or open a report at https://github.com/thisisforlearn/newpgp/issues and tell us what you saw.

**Coming soon:** NewPGP is coming to more platforms, including Intel Macs. Watch the official page for links.

---

## About NewPGP

- Official project: https://github.com/thisisforlearn/newpgp
- This version: v0.1.3 — https://github.com/thisisforlearn/newpgp/releases/tag/v0.1.3
- Author: **Vaibhav** ([@thisisforlearn](https://github.com/thisisforlearn))
- Contributor: [@sudo-linux-neko](https://github.com/sudo-linux-neko)
- Licence: **GPL-3.0-or-later** (free software)
- Contact: **nekomeowpurr@proton.me**
