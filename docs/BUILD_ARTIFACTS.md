# Getting an APK and a desktop app

Official project: https://github.com/thisisforlearn/newpgp
Contact: nekomeowpurr@proton.me

## Android keyboard APK

The repository does not commit the Gradle wrapper JAR or an Android SDK, and
this development machine has no JDK/SDK installed. The APK is therefore built by
GitHub Actions:

1. Open https://github.com/thisisforlearn/newpgp/actions/workflows/android-apk.yml
2. Click **Run workflow** (on the `main` branch).
3. When the job finishes, download `newpgp-keyboard-debug` from the run page.
4. Unzip it and install `app-debug.apk` on the phone. Android will ask to allow
   installing unknown sources; the keyboard must then be enabled in
   **Settings → Languages & input → On-screen keyboard**.

For a signed release APK, create a signing keystore and add the four secrets
`NEWPGP_KEYSTORE_BASE64`, `NEWPGP_KEYSTORE_PASSWORD`, `NEWPGP_KEY_ALIAS`,
`NEWPGP_KEY_PASSWORD`, then push a `v*` tag. The release job publishes the APK
to the GitHub release.

## Desktop app (Windows / macOS / Linux)

1. Open https://github.com/thisisforlearn/newpgp/actions/workflows/desktop-release.yml
2. Click **Run workflow**, or push a `v*` tag.
3. Download the artifact for your OS: `.msi`/`.exe` (Windows), `.dmg` (macOS),
   `.deb`/`.AppImage` (Linux).

Locally, with Node 24 and Rust installed:

```bash
cd apps/desktop
npm ci
npm run tauri dev     # development
npm run tauri build   # installer in src-tauri/target/release/bundle/
```

## CLI binaries

Run https://github.com/thisisforlearn/newpgp/actions/workflows/cli-release.yml
with **Run workflow** to build `newpgp` for Windows, macOS and Linux. Each
binary is uploaded as a separate artifact, and is attached to the GitHub release
when a `v*` tag is pushed.

## CLI from source

```bash
cargo build --release -p newpgp-cli
# binary: target/release/newpgp.exe (Windows) or target/release/newpgp
```

## Note on platforms

NewPGP will be available on more platforms over time; setting each one up takes
time. Links to every official platform are listed on the project page:
https://github.com/thisisforlearn/newpgp
