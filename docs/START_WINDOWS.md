# NewPGP on Windows — easy start (v0.1.3)

This guide is for a Windows computer. It should take about **five minutes**. Go back to [Start here](../GETTING_STARTED.md) any time.

> **Please know this first:** v0.1.3 has **not** been checked by outside security experts. Do not rely on it for life-or-death secrets.

---

## 1. Download the installer

1. Open the download page: https://github.com/thisisforlearn/newpgp/releases/tag/v0.1.3
2. Find the file named `newpgp-0.1.3-windows-x64.msi`.
   - You may also see `NewPGP_0.1.3_x64_en-US.msi` or `newpgp-0.1.3-windows-x64-setup.exe`. Both names are in this release. Pick **one**.
3. Click it. Your browser saves it in **Downloads**.

**You will see:** the file in your Downloads folder.

---

## 2. Install it

1. Open **Downloads** in File Explorer.
2. Double-click the file you downloaded.
3. If Windows asks "Do you want to allow this app to make changes?", click **Yes**.
4. Click **Next**, then **Install**, then **Finish**.

**You will see:** a window that says NewPGP is installed. A NewPGP icon appears in your Start menu.

**If Windows says "Windows protected your PC":** click **More info**, then **Run anyway**. Do this only because you downloaded the file from the official project page above.

---

## 3. Make your key ("Create my key")

1. Open NewPGP from the Start menu. A welcome card appears. It says **Welcome to NewPGP** and **Step 1 of 3**.
2. In **Your name**, type your name.
3. In **Your email**, type your email address.
4. In **Password**, type a password you will remember. You may leave it empty; NewPGP then makes a backup code instead. If you use a password, write it down — there is **no way to recover it**.
5. Click the blue button labelled **Create my key**.

**You will see:** **Step 2 of 3** and a short code made of letters and numbers. That is your key. It stays on your computer.

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
| "Windows protected your PC" | Click **More info**, then **Run anyway**. Only do this for the file from the official page above. |
| I cannot find **Create my key** | On the welcome card it is the big blue button. If the card is gone, click **Keys & people**, then **Create a new key** to open that section. |
| "I need a key for that person" | Ask your friend for their public key. In NewPGP go to **Keys & people** → **Add key**, paste their key, click **Add key**, then try again. |

---

## Need help?

Email **nekomeowpurr@proton.me**. Or open a report at https://github.com/thisisforlearn/newpgp/issues and tell us what you saw.

**Coming soon:** NewPGP is coming to more platforms. Watch the official page for links.

---

## About NewPGP

- Official project: https://github.com/thisisforlearn/newpgp
- This version: v0.1.3 — https://github.com/thisisforlearn/newpgp/releases/tag/v0.1.3
- Author: **Vaibhav** ([@thisisforlearn](https://github.com/thisisforlearn))
- Contributor: [@sudo-linux-neko](https://github.com/sudo-linux-neko)
- Licence: **GPL-3.0-or-later** (free software)
- Contact: **nekomeowpurr@proton.me**
