# NewPGP Keyboard (Android IME)

A Kotlin Android soft keyboard for NewPGP with strong on-device autocorrect and an explicit
"tap to encrypt" key. It is a normal `InputMethodService` that can be enabled under
**Settings → Languages & input → On-screen keyboard → NewPGP Keyboard**.

## Status of the crypto bridge

**The Rust core is NOT wired up in this module.** The shield key runs the complete UI flow,
but the default `NativePgpBridge` deliberately fails closed with a visible status message
("Encryption unavailable: the native PGP bridge is not connected in this build yet").
No fake ciphertext is ever produced in release builds. Unit tests use `MockPgpBridge`,
which emits clearly-labelled fake armor and is not real encryption.

## Features

- QWERTY keyboard with dedicated shift (tap = once, double-tap = caps lock), backspace,
  numbers/symbols layers, emoji layer, and editor-action-aware Enter.
- Three-suggestion strip with a position-sensitive algorithm combining Damerau (OSA)
  edit distance, word frequency, prefix completion and learned words.
- Sentence-start capitalization, always-capital "I", and punctuation spacing rules.
- System-following light/dark Material 3 UI, custom-rendered compact keycaps.
- **Recommended default mode:** tap the shield key to encrypt the current composing word
  (or the whole field when no word is being composed) and replace it with the armored block.
  A status banner and a toast confirm that encryption was attempted, and failures are visible.
- **Optional, OFF by default:** auto-append mode (see below).
- Pure-Kotlin autocorrect engine with JUnit tests that run on the JVM, no emulator needed.

## Privacy

- No keystroke logging, no analytics, no network permission, no `INTERNET` use.
- Composing text lives only in an in-memory `StringBuilder` for the current input session.
- The **only** declared text egress is the `PgpBridge` interface. Currently it fails closed.
- No typed text is written to disk. App-private `SharedPreferences` store only two settings
  and learned-word counts (single lowercase words, not sentences). Cloud backup and device
  transfer are disabled via `data_extraction_rules.xml`.

## The two modes and their security tradeoff

### 1. Tap-to-encrypt (default, recommended)

The user types a message, then presses the shield key. The current word/field text is passed
to `PgpBridge.encrypt()` and the ASCII-armored result replaces it in the field. Nothing is
sent or appended automatically: the user decides what to do with the armored block.

### 2. Auto-append encrypted block (experimental, default OFF)

Setting key `auto_append_encrypted_block` makes Enter encrypt the whole field and append the
armored block after a blank line. A red warning is shown in Settings and the toggle cannot
be enabled without ticking an acknowledgement in a confirmation dialog.

**Warnings (also shown in the UI):**

- The plaintext may already have been saved in the other app's draft/autosave *before* the
  keyboard encrypts it. This mode does not undo that leak.
- The IME cannot detect a real "send" event. Enter may commit text without sending, so an
  armored block can end up inside an unsent draft.
- Some apps reject or mangle appended multi-line text; compatibility is not guaranteed.

Keep this OFF unless you accept those risks. A visible status banner marks every auto-append.

## Building

There is no local Android SDK in this repository checkout, so the Gradle configuration is
written for CI. GitHub Actions should install `platforms;android-36` and
`build-tools;36.0.0`, set `ANDROID_HOME`/`ANDROID_SDK_ROOT`, use JDK 17, then run:

```bash
cd android/keyboard
./gradlew --no-daemon :app:assembleDebug :app:testDebugUnitTest
```

Plain Gradle without an SDK can still run `:app:testDebugUnitTest` only after an SDK
platform is installed (AGP requires it during configuration).
`gradle/wrapper/gradle-wrapper.properties` targets Gradle 8.13. Commit the standard Gradle
wrapper JAR at `gradle/wrapper/gradle-wrapper.jar` (generated once with `gradle wrapper`)
if the CI workflow does not use a preinstalled Gradle distribution. AGP 8.11.1, Kotlin 2.2.0,
`compileSdk = 36`, `minSdk = 24`.

See [VERIFY_BUILD.md](VERIFY_BUILD.md) for the exact CI commands, the signing-secret gate, and
local reproduction steps.

## Source layout

- `app/src/main/java/com/newpgp/keyboard/autocorrect/` — pure Kotlin dictionary,
  Damerau-Levenshtein, ranking engine, casing/spacing helpers, learned words.
- `.../KeyLayouts.kt`, `KeyboardView.kt`, `SuggestionStripView.kt` — programmatic UI.
- `.../NewPgpImeService.kt` — IME lifecycle, composing region, editor actions, encryption flow.
- `.../PgpBridge.kt`, `MockPgpBridge.kt`, `NativePgpBridge.kt`, `EncryptionController.kt` — crypto seam.
- `.../settings/` — settings and the learned-word persistence hook.
- `app/src/test/java/...` — JVM unit tests (misspellings, "I", punctuation, unknown words,
  distance limits, learned words, bridge behaviour).

## Connecting the native bridge (phase 2, not implemented here)

1. Build `newpgp-core` for Android ABIs with UniFFI scaffolding enabled.
2. Add the generated Kotlin bindings and `.so` files under `app/src/main/jniLibs/<abi>/`.
3. In `NativePgpBridge.encrypt`, call the generated API (e.g. an armored encrypt function
   taking the plaintext and recipient fingerprint), map failures into `Result.failure`, and
   avoid retaining plaintext copies.
4. Add recipient/key selection to `SettingsActivity`, then make `PgpBridgeProvider.current`
   return the native bridge. Keep `ENABLE_DEMO_PGP_BRIDGE = false`.

Until step 3 is done, `NativePgpBridge` must keep failing closed.

## Known limitations

- The native encrypt/decrypt implementation, key management, and recipient selection are out
  of scope for this module.
- Autocorrect's built-in dictionary is a compact common-English list (~350 words), not a
  full language model; next-word prediction is not implemented.
- The IME does not implement clipboard, voice input, or multi-locale subtypes.
- Unit tests cover pure logic only; UI behaviour requires a device/emulator.
