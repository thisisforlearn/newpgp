# NewPGP for iOS

This directory is the iOS scaffold. It is a SwiftUI app shell plus a Swift
protocol (`PgpBridge`) that describes the exact operations the UI needs. The
default implementation is an **in-memory mock** so the interface and tests can
be developed without the Rust engine; `RustPgpBridge` currently fails closed.

## Current status

- SwiftUI UI: key creation, compose with the send-both default, contacts, notices.
- `PgpBridge`: typed contract shared by mock and real implementation.
- Real cryptography: **not wired yet**. Phase 2 adds a `newpgp-mobile` Rust crate
  exposing the existing `newpgp-core` / `newpgp-keyring` / `newpgp-discovery`
  crates through UniFFI, then replaces `MockPgpBridge` with the generated bindings.

## Building

A Mac with Xcode 16+ is required. This repository's CI builds the Android APK on
Linux; iOS releases need a macOS runner with signing credentials and are not
enabled yet.

1. Open this folder as an Xcode project target (or add the files to a new
   SwiftUI app target).
2. Add the generated UniFFI bindings and replace `model.bridge`.
3. Enable the iOS job in `.github/workflows/` once signing is configured.

## Design notes

- No plaintext is written to `UserDefaults` or documents by this UI.
- The mock emits clearly labelled fake armor; never ship it in a release build.
- The iOS sharing flow will use an "Encrypt & Share" action extension rather
  than keyboard interception, which is both more reliable and less invasive.
