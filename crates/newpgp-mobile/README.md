# newpgp-mobile

<!-- SPDX-License-Identifier: GPL-3.0-or-later -->
<!-- Copyright (C) 2026 Vaibhav - https://github.com/thisisforlearn -->

The real on-device OpenPGP bridge for the NewPGP Android keyboard. It reuses
`newpgp-core` and `newpgp-keyring`; there is no custom cryptography, no network
access and no logging of plaintext, passwords or keys.

## What ships

The Kotlin object `com.newpgp.keyboard.NewPgpNative` loads exactly one library:

```kotlin
companion object { init { System.loadLibrary("newpgp_mobile") } }
```

The packaged artifact is **`libnewpgp_mobile.so`** in each ABI directory. It is
built by the Android CMake project at
`android/keyboard/app/src/main/CMakeLists.txt` from:

* `cpp/newpgp_jni.cpp` — the JNI shim (UTF-16 ⇄ UTF-8 conversion, plain-language
  Java exceptions, `JNI_OnLoad`); and
* `libnewpgp_mobile.a` — the Rust `staticlib` produced by cargo-ndk, which
  exports a tiny C ABI (`newpgp_init`, `newpgp_version`,
  `newpgp_generate_identity`, `newpgp_public_key`, `newpgp_encrypt`,
  `newpgp_decrypt`, `newpgp_safety_number`, `newpgp_free_string`).

The crate is also a `cdylib` named `newpgp_mobile` so that a plain
`cargo ndk ... build --release` deterministically emits `libnewpgp_mobile.so`
in every ABI output directory (CI checks for that filename). That cdylib is a
build artifact only; Gradle does not add its directory to `jniLibs`, because
CMake already provides the JNI-bearing library with the same name.

Crate types: `rlib` (host checks/tests), `staticlib` (CMake input), `cdylib`
(cargo-ndk artifact). Host `cargo check` uses the non-Android/JNI stub, so no
NDK is needed on Windows.

## Build

Prerequisites: Rust ≥ 1.88 (the rPGP 0.21.1 MSRV), cargo-ndk ≥ 1.86, the NDK
and the Rust Android targets.

```bash
cargo install cargo-ndk
rustup target add aarch64-linux-android armv7-linux-androideabi x86_64-linux-android

# Host validation (no NDK required):
cargo test --manifest-path crates/newpgp-mobile/Cargo.toml

# Native libraries for Android (from the repository root):
cargo ndk -t arm64-v8a -t armeabi-v7a -t x86_64 \
  -o android/keyboard/app/build/newpgp/jniLibs \
  build --release --manifest-path crates/newpgp-mobile/Cargo.toml
```

Gradle does this automatically through `:app:buildNewpgpRust`, which is wired
to the `externalNativeBuild`/`configureCMake` tasks. If `cargo ndk --version`
fails (tool not installed), that task is skipped so Kotlin-only unit tests keep
working; a full native build will then fail with a clear CMake error about the
missing static library. `ANDROID_NDK_HOME` is honoured by cargo-ndk if the NDK
is not in the default SDK location.

ABIs: `arm64-v8a` (`aarch64-linux-android`), `armeabi-v7a`
(`armv7-linux-androideabi`), `x86_64` (`x86_64-linux-android`). `x86` is not
shipped; add `i686-linux-android` to both lists if an emulator needs it.

Optional alternative: `--features direct-jni --target aarch64-linux-android`
compiles the `jni`-crate entry points in `src/jni_direct.rs`, which export the
same eight JNI symbols straight from Rust. Do not link that into the same
`.so` as the C++ shim (symbol collision).

### Approximate size

A release build with thin LTO, `panic = "abort"` and debuginfo stripping adds
roughly 2–4 MB per ABI to the APK (rPGP and its pure-Rust dependencies dominate).
Only three ABIs are packaged, and architecture-specific `.so` files are not
duplicated on a device. Recompute with `cargo ndk ... build --release` and
`ls -lh target/<triple>/release/libnewpgp_mobile.so` before quoting a number in
a release note.

## Why plain JNI, not UniFFI

UniFFI would add the `uniffi` scaffolding dependency, a `uniffi-bindgen`
generation step, generated Kotlin sources, JNA ≥ 5.12 and (for futures)
kotlinx-coroutines to a keyboard APK. This bridge has a fixed, eight-function,
string-in/string-out ABI that is already dictated by the Kotlin object, so a
hand-written JNI shim is smaller, has no generated code to keep in sync, and
lets us throw exactly the plain-language exceptions we want. The only
additional Rust dependency in the default build is `serde_json` for the two
JSON result shapes.

## Storage and threat model (honest)

`NewPgpNative.init(filesDirAbsolutePath)` must be called once from Kotlin with
`filesDir.absolutePath`. Everything is written below `<filesDir>/newpgp/`:

| File | Contents | Protection today |
|---|---|---|
| `identity.json` | fingerprint, armored public and private key | app-private sandbox; Unix mode 0600; atomic replace |
| `keyring.json` | contact public keys, TOFU state, safety numbers | app-private sandbox; Unix mode 0600; atomic replace |

Facts a reviewer should not miss:

* **If the identity was created with an empty password, the private key armor
  is stored unencrypted** inside app-private storage. Android's app sandbox is
  the only barrier: a rooted device, a backup extracted by an attacker, or a
  debug build with world-readable export can recover it. A non-empty password
  applies rPGP's standard S2K protection to the secret key packets, but that
  password is currently passed in from Kotlin per call and is **not** held in
  the Android Keystore.
* There is no hardware-backed wrapping yet. The next step is to generate or
  wrap a symmetric key in Android Keystore and use it for `identity.json`
  at rest, with a user-presence gate for signing.
* TOFU is deliberately conservative: a changed contact key is parked as a
  pending change and never silently promoted. `safetyNumber(email)` is the
  out-of-band check; it is a verification aid, not a secret.
* The bridge fails closed. Missing identity, missing recipient and bad armor
  produce plain-language Java exceptions; no fake ciphertext is ever returned.
* Neither layer logs the plaintext, the password, the key armor or file paths.

## JNI contract

All functions are `static` on `com.newpgp.keyboard.NewPgpNative`, take/return
`String` (except `encrypt`'s `jboolean sign`), and throw
`java.lang.RuntimeException` with a plain-language message on failure.
`init` returns a `String` (empty on success) but may be declared as returning
`Unit` in Kotlin — the JNI short name does not encode the return type.

| JNI symbol | Kotlin declaration |
|---|---|
| `Java_com_newpgp_keyboard_NewPgpNative_init` | `init(filesDirAbsolutePath: String)` |
| `Java_com_newpgp_keyboard_NewPgpNative_version` | `version(): String` |
| `Java_com_newpgp_keyboard_NewPgpNative_generateIdentity` | `generateIdentity(name: String, email: String, password: String): String` |
| `Java_com_newpgp_keyboard_NewPgpNative_publicKey` | `publicKey(): String` |
| `Java_com_newpgp_keyboard_NewPgpNative_encrypt` | `encrypt(plaintext: String, recipientsCsv: String, sign: Boolean, password: String): String` |
| `Java_com_newpgp_keyboard_NewPgpNative_decrypt` | `decrypt(armored: String, password: String): String` |
| `Java_com_newpgp_keyboard_NewPgpNative_safetyNumber` | `safetyNumber(email: String): String` |

`generateIdentity` returns
`{"fingerprint":"...","publicKey":"-----BEGIN...","privateKey":"-----BEGIN..."}`;
`decrypt` returns `{"plaintext":"...","signature":"verified|unverified|unsigned"}`.
`recipientsCsv` accepts comma-separated emails known to the keyring and/or
armored public keys; armored keys are learned with TOFU so later email lookup
and safety numbers work. Encryption always uses SEIPDv1 + AES-256 for maximum
client compatibility. An invalid signature from a known signer is reported as
`unverified` because the public contract has only three states.
