# NewPGP in a browser — easy start (v0.1.3)

This guide is for **Gmail or Outlook on the web**, using Chrome, Edge, or Firefox on a computer. A browser add-on is a small helper that adds a button to the browser you already use.

> **Please know this first:** v0.1.3 has **not** been checked by outside security experts. Do not rely on it for life-or-death secrets.

> **This is the fiddliest setup.** The add-on cannot lock messages by itself. It needs a small helper program on your computer. If you want the quickest five-minute path, use the desktop app instead: [Windows](START_WINDOWS.md), [Mac](START_MACOS.md), [Linux](START_LINUX.md).

> **Send both is the default.** Your words stay as normal text **plus** a locked copy underneath. People who do not use PGP can still read your email. Turn on **Send PGP only** in the NewPGP popup if you want only the locked copy.

---

## 1. Download what you need

On the release page https://github.com/thisisforlearn/newpgp/releases/tag/v0.1.3 download:

1. `newpgp-browser-0.1.3.zip` — the add-on.
2. The helper program for your computer:
   - Windows: `newpgp-cli-0.1.3-windows.exe`
   - Mac: `newpgp-cli-0.1.3-macos`
   - Linux: `newpgp-cli-0.1.3-linux`

**You will see:** files in your Downloads folder.

> There is no store link on the v0.1.3 release page. Use these files. The Firefox file `newpgp-browser-0.1.3.xpi` is **not signed**, so normal Firefox will not install it. The steps below use the ZIP instead.

---

## 2. Unzip the add-on

1. Find `newpgp-browser-0.1.3.zip` in Downloads.
2. Right-click it and choose **Extract All** (Windows) or double-click it (Mac/Linux).
3. Put the unzipped folder somewhere you will not delete, for example **Documents → NewPGP add-on**.

**You will see:** a folder containing a file called `manifest.json`. Keep this folder.

---

## 3. Load the add-on in Chrome or Edge

1. Open Chrome or Edge.
2. In the address bar, type `chrome://extensions` (Chrome) or `edge://extensions` (Edge) and press Enter.
3. Turn on **Developer mode** using the switch near the top right.
4. Click **Load unpacked**.
5. Choose the unzipped folder from step 2.

**You will see:** a NewPGP card in the list, with an extension ID written on it (32 small letters). Write that ID down.

---

## 4. Connect the helper program (needed once)

The helper is what does the locking and unlocking. It stays on your computer. The browser starts it when needed.

### On Windows

1. Create a folder `C:\NewPGP`.
2. Move `newpgp-cli-0.1.3-windows.exe` into it and rename it to `newpgp.exe`.
3. On the project page, click **Code** → **Download ZIP**. Unzip it. This gives you the setup script called `install.ps1`.
4. Open PowerShell in that unzipped folder. (In File Explorer, right-click inside the folder and choose **Open in Terminal**.)
5. Type this, replacing `YOUR_ID` with the ID you wrote down:

   `.\native-host\install.ps1 -ChromeExtensionId YOUR_ID -Executable "C:\NewPGP\newpgp.exe"`

   This just tells Windows where the helper lives and which add-on is allowed to use it.

6. Close and reopen the browser. On the extensions page, click the reload button on the NewPGP card.

### On Mac or Linux

1. Create a folder `NewPGP` in your home folder. Move the helper file there and rename it to `newpgp`.
2. Open Terminal in that folder and type:

   `chmod +x ~/NewPGP/newpgp`

   This only gives the file permission to run.
3. Download the project source from the project page (**Code** → **Download ZIP**) and unzip it.
4. Open Terminal in the unzipped source folder and type:

   `./native-host/install.sh YOUR_ID "" ~/NewPGP/newpgp`

   Replace `YOUR_ID` with the ID you wrote down. The `""` means "no Firefox ID". This step tells your computer where the helper lives.
5. Close and reopen the browser, then reload the add-on.

---

## 5. Check it worked

1. Click the NewPGP icon in the browser toolbar.
2. Look at the top of the popup.

**You will see:** **Helper connected**. If it says **Checking helper…**, wait a moment and click the add-on again.

---

## 6. Make your key

The add-on does not make the key itself. Do this once, before your first message.

**Easiest way:** install the desktop app and click **Create my key** there ([Windows](START_WINDOWS.md), [Mac](START_MACOS.md), [Linux](START_LINUX.md)).

**Or with the helper:** ask someone comfortable with a computer to open a Terminal or PowerShell window in the `NewPGP` folder and run:

`newpgp setup`

This asks three plain questions — your name, your email address, and a password — then makes your key. It stays on your computer.

---

## 7. Send your first private message

1. Open Gmail or Outlook on the web in the same browser.
2. Click **Compose** (Gmail) or **New mail** (Outlook).
3. Fill in **To** and type your message.
4. Click **Send** as usual.

**You will see:** the message is sent. The add-on quietly adds a locked block underneath your words before sending. If it cannot lock the message, it **stops the send** and shows a notice instead of sending plain text.

**Want only the locked copy?** Open the NewPGP popup and turn on **Send PGP only**. Then people who do not use PGP cannot read your email.

> **Gmail and Outlook may save a draft before NewPGP adds the locked copy.** For very private mail, use the desktop app and paste the finished text yourself.

---

## 8. Read a private message

1. Open the email your friend sent.
2. Look for the locked block that starts with `-----BEGIN PGP MESSAGE-----`.
3. Click the button labelled **Decrypt with NewPGP**.
4. Read the result.

**You will see:** the message words, and a status: **Verified**, **Unverified — verify by comparing the safety number**, or **Invalid**.

---

## Firefox note

Firefox 142 or newer is needed. Open `about:debugging`, click **This Firefox**, then **Load Temporary Add-on**, and choose `manifest.json` in the unzipped folder. The add-on disappears when you close Firefox, so you must load it again next time. The `newpgp-browser-0.1.3.xpi` file is unsigned and will not install in normal Firefox. To use the helper with Firefox, the add-on ID is `newpgp@newpgp.local`.

---

## If something goes wrong

| What you see | What to do |
| --- | --- |
| Popup says **Checking helper…** or **Helper not found** | Check that the ID you typed is exactly the one on the extensions page. Then close the browser, reopen it, and reload the add-on. On Windows, check that the helper file is at `C:\NewPGP\newpgp.exe`. |
| **Send** seems to do nothing | Click **Send** a second time. Some webmail pages need a second click. If a NewPGP notice appears, read it — the add-on may be stopping the send because it could not lock the message. |
| No **Decrypt with NewPGP** button | Make sure the whole locked block is in the email, from BEGIN to END. Reload the email page. The button appears only when a complete block is found. |

---

## Need help?

Email **nekomeowpurr@proton.me**. Or open a report at https://github.com/thisisforlearn/newpgp/issues and tell us what you saw.

**Coming soon:** store listings and more platforms are being prepared. Watch the official page for links.

---

## About NewPGP

- Official project: https://github.com/thisisforlearn/newpgp
- This version: v0.1.3 — https://github.com/thisisforlearn/newpgp/releases/tag/v0.1.3
- Author: **Vaibhav** ([@thisisforlearn](https://github.com/thisisforlearn))
- Contributor: [@sudo-linux-neko](https://github.com/sudo-linux-neko)
- Licence: **GPL-3.0-or-later** (free software)
- Contact: **nekomeowpurr@proton.me**
