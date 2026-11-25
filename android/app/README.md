# NewPGP Companion App (Android)

A Jetpack Compose / Material 3 companion app for NewPGP. It is a normal, installable
app for non-technical users — **not** a keyboard or an IME. It has no access to
what you type in other apps.

## Honest status (please read first)

**There is no real cryptography in this build.** The default bridge is
`NativePgpBridge`, which fails closed with a visible message: *"the native PGP
bridge is not connected in this build yet"*. No fake ciphertext is ever
presented as real. `MockPgpBridge` produces clearly marked `NewPGP MOCK` armor
and is used only by JVM unit tests and by an optional local demo flag
(`ENABLE_DEMO_PGP_BRIDGE`, which must never be enabled in a release).

Phase 2 wiring is documented in `NativePgpBridge.kt`: build `newpgp-core` for
Android ABIs with UniFFI, add the generated Kotlin bindings and `.so` files,
move long-lived secret key material into the Android Keystore, then switch
`BridgeProvider.current` to the native bridge.

## Features (UI flows are present; crypto is not)

- **Onboarding:** create a local identity and copy an armored public key.
- **Write tab:** compose a message for one or more recipients. Default is
  **send both** (plain text followed by the armored block). A clearly explained
  **PGP-only** switch removes the plain text. Copy or share the result through
  the system share sheet.
- **Read tab:** paste an armored message, decrypt, and see an explicit
  signature status (`VERIFIED`, `SIGNED_UNVERIFIED`, `NOT_SIGNED`, `UNKNOWN`).
- **People tab:** list contacts, look up a key by email (placeholder for WKD),
  display a fingerprint-derived safety number, and compare it with one read
  out-of-band. Verified contacts are labelled.
- **Files tab:** explicitly marked preview-only. No file is read, uploaded,
  or stored; the share button is disabled.
- **Share target:** accepts an explicit `ACTION_SEND` of `text/plain` from
  another app and offers **Encrypt & copy** / **Encrypt & share**. Nothing is
  captured automatically and nothing happens until a button is tapped.
- Material 3 light/dark theming with optional Android 13+ dynamic color.

## Privacy notes

- **No keystroke capture.** This app is not an input method and runs no
  background service. The separate `android/keyboard` module handles typing.
- **No plaintext logging.** Text, keys, and results live in in-memory Compose
  state only. There is no analytics, crash reporting, or telemetry.
- **No backups.** `android:allowBackup="false"` plus `data_extraction_rules.xml`
  exclude app data from cloud backup and device-to-device transfer.
- **No dangerous permissions.** The only permission is `INTERNET`, reserved for
  future WKD key discovery over HTTPS. `android:usesCleartextTraffic="false"`
  forbids plaintext network traffic. No network call is made by the mock or
  native-stub bridges.
- Clipboard writes happen only after an explicit Copy tap; the app never reads
  the clipboard.

## Building

There is no Android SDK or JDK in this checkout, so CI performs the build.
Use JDK 17, Gradle 8.13, AGP 8.11.1, Kotlin 2.2.0, `compileSdk = 36`,
`minSdk = 24`, `targetSdk = 36`.

CI should install the SDK platform and build tools first:

```bash
yes | sdkmanager --licenses > /dev/null 2>&1 || true
sdkmanager "platforms;android-36" "build-tools;36.0.0"
export ANDROID_HOME=/usr/local/lib/android/sdk
export ANDROID_SDK_ROOT=$ANDROID_HOME
```

Then, from `android/app`:

```bash
./gradlew --no-daemon assembleDebug testDebugUnitTest
```

If a Gradle wrapper JAR is not committed, use a preinstalled Gradle 8.13 on
`PATH` and run `gradle --no-daemon assembleDebug testDebugUnitTest` instead.
`gradle/wrapper/gradle-wrapper.properties` already targets Gradle 8.13; commit
the standard wrapper JAR (`gradle wrapper`) if CI does not preinstall Gradle.

The Compose BOM is deliberately pinned to `2025.11.00`: Compose 1.12+ requires
`compileSdk 37` and AGP 9, which would break the requested API 36 toolchain.

## Source layout

```
app/src/main/java/com/newpgp/app/
  PgpBridge.kt        interface + shared data models
  MockPgpBridge.kt    deterministic fake bridge (tests / local demo only)
  NativePgpBridge.kt  failing-closed stub with the Phase 2 UniFFI/JNI plan
  BridgeProvider.kt   selects the bridge
  AppModel.kt         Android-free state holder (StateFlow + coroutines)
  MainActivity.kt     onboarding, Write, Read, People, Files tabs
  ShareActivity.kt    explicit ACTION_SEND text/plain target
  ui/theme/           colors, typography, Material 3 theme, shared composables
app/src/test/java/com/newpgp/app/   JVM-only JUnit tests (no device needed)
```

## Known limitations / risks

- No real OpenPGP operations, key storage, WKD lookup, or file encryption yet.
- The mock bridge is deterministic; it is not secure and must never ship.
- Compose UI behavior needs a device or emulator to verify visually. JVM tests
  cover the bridge, state holder, compose/decrypt, safety numbers, and sharing.
- Configuration was written by inspection (no local JDK/SDK). XML was checked
  for well-formedness; Gradle sources and Kotlin were reviewed manually. The
  first CI run may still surface dependency-resolution or lint issues.
- CI paths must be extended separately to build `android/app/**`; this module
  does not modify any workflow file.
