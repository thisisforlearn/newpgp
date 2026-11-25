# CI health — NewPGP

Official project: https://github.com/thisisforlearn/newpgp
Maintainer: Vaibhav (@thisisforlearn) — nekomeowpurr@proton.me
Contributor: @sudo-linux-neko

This page records real workflow failures found on tag `v0.1.1` and how they are
being fixed. It is updated after each verification pass.

## Failure inventory (observed 2026-11-25)

| Workflow | Run | Failure | Fix owner | Status |
|---|---|---|---|---|
| Rust | 45689133123 | `cargo audit` advisories + cargo-deny v2 schema errors + two GPL-compatible licenses not in the allow list | narrow audit ignores, cargo-deny v2 migration, `bzip2-1.0.6` and `CDLA-Permissive-2.0` allowed | audit/clippy/fmt green; cargo-deny re-running |
| Android APK | 45689139845 | `android-actions/setup-android@v3` runs removed `sdkmanager tools` package and crashes | use preinstalled SDK on GitHub runners, pin Gradle 8.13 | ✅ green (also fixed invalid IME `android:locale` resource) |
| Desktop Release | 45689139743 | 53 Rust errors in Tauri backend: `newpgp_core::Result` alias used with two generic args, Tauri commands returning a non-serializable error | command signatures to `Result<T, String>` | ✅ green on Windows, macOS and Linux |
| Docs | 45696646355 | lychee failed on a handshake error for fsf.org and redirect links | replaced with stable GNU docs URLs, updated redirects | ✅ green |
| CLI Binaries | 45689139662 / 45689141547 | ✅ passed | — | done |
| Browser Extension | 45688761966 | ✅ passed (51 tests) | — | done |

## Why the audit advisories are not a reason to disable the check

NewPGP generates and uses **Ed25519 signing keys and Curve25519/X25519
encryption subkeys** only. The RSA and crypto-bigint code paths are legacy
parsing compatibility inside rPGP; they are not used by NewPGP's own operations.
There is no patched rPGP release that upgrades those dependencies without
breaking API changes. The honest fix is a scoped, documented ignore with a
review condition — see `docs/SECURITY_AUDIT.md` and `.cargo/audit.toml`.

## Dependabot noise

24 open Dependabot PRs include many breaking major upgrades (rand 0.10, base64
0.23, sha2 0.11, hmac 0.13, aes-gcm 0.11, argon2 0.6, TypeScript 7, vitest 5,
Gradle 9, AGP 9, GitHub action majors). These need dedicated migration work and
must not be auto-merged. Decisions are recorded in
`docs/DEPENDABOT_TRIAGE.md`.

## End-to-end verification

Run locally:

```bash
cargo fmt --all -- --check
cargo clippy --workspace --all-targets -- -D warnings
cargo test --workspace
cargo test -p newpgp-cli --test e2e
npm test   # in extensions/browser
```

See `docs/E2E_TESTING.md` for the full CLI/bridge flow and
`docs/BUILD_ARTIFACTS.md` for APK/desktop/CLI artifacts.
