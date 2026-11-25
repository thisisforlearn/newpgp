# Dependabot triage — 24 open PRs (2026-11-26)

Project: https://github.com/thisisforlearn/newpgp
Contact: nekomeowpurr@proton.me

## Summary

22 of the 24 open Dependabot PRs were **closed** because they are breaking/major
upgrades that require a dedicated migration PR. Nothing was merged and no branches
were deleted. PRs **#1** and **#4** were **kept open** (compatible minor bumps).
`.github/dependabot.yml` now holds the closed versions under `ignore` rules so
Dependabot does not immediately reopen them, while patch/minor updates continue
on the current version lines.

> Note on CI: at triage time some of the breaking PRs showed green checks (CI does
> not exercise the changed dependency paths), while the two kept-open Android minor
> bumps showed a failing `Build debug APK` check. Decisions below are based on
> upstream breakage, not solely on the current check state. The Android APK failure
> needs a separate investigation — it is not caused by a major-version bump.
> Update: the Android failure was fixed in the workflow; both kept PRs were
> rebased onto the fixed `main` and their checks re-run.

### Follow-up (same day)

Dependabot recreated a few of the held upgrades at nearby versions (#25–#30).
Those were also closed, and the `ignore` rules were changed from exact versions
to **version ranges** (`>=0.11.0`, `>=0.10.0`, etc.) so the whole held line is
blocked instead of one release. Patch updates on the currently used lines still
flow normally.

## Decisions

| PR | Package | From → To | Decision | Why | When to revisit |
|---:|---|---|---|---|---|
| 1 | `com.google.android.material:material` (android) | 1.12.0 → 1.14.0 | **Kept open** | Minor 1.x bump, no migration expected. | Review the `Build debug APK` failure; merge once green. |
| 2 | `typescript` (extension) | 5.9.3 → 7.0.2 | **Closed** | TS 7 is the native Go port: new defaults (`strict`, `rootDir`, `types`), dropped ES5/`downlevelIteration`/`baseUrl`/legacy module resolution, no compiler API in 7.0. | After adopting TS 6 behavior and verifying tsconfig + typescript-eslint/tooling compatibility. |
| 3 | `actions/setup-java` | 4 → 6 | **Closed** | Major action bump; runner/JDK defaults and inputs can change. | After updating and validating the Java CI workflow. |
| 4 | `androidx.appcompat:appcompat` (android) | 1.7.0 → 1.8.0 | **Kept open** | Minor 1.x bump, no migration expected. | Review the `Build debug APK` failure; merge once green. |
| 5 | `actions/checkout` | 4 → 7 | **Closed** | Major action bump; ESM runtime and fork-PR checkout restrictions. | After auditing workflow checkout inputs and fork-PR behavior. |
| 6 | `softprops/action-gh-release` | 2 → 3 | **Closed** | Major action bump; inputs/permissions may change. | After validating release workflows end-to-end. |
| 7 | `vitest` (extension) | 2.1.9 → 5.1.1 | **Closed** | Requires Node ≥22.12 and Vite ≥6.4; breaking config, mocking, reporter and benchmark API changes. | After Node/Vite toolchain upgrades and test migration (Vitest 4 → 5 guide). |
| 8 | `com.android.application` (AGP) | 8.11.1 → 9.5.3 | **Closed** | AGP 9 requires Gradle 9.6+ and newer SDK/Build Tools; must be coordinated with Kotlin/Gradle. | After a combined Gradle 9 + Kotlin 2.4 + AGP 9 migration PR. |
| 9 | `sha1` (cargo) | 0.10.7 → 0.11.0 | **Closed** | RustCrypto `digest` 0.11: newtypes replace aliases, feature removals, MSRV 1.85. | With the coordinated RustCrypto `digest` 0.11 migration. |
| 10 | `gradle/actions` | 4 → 6 | **Closed** | Major action bump; setup-gradle/caching behavior changes. | After validating Android CI workflow on v6. |
| 11 | `esbuild` (extension) | 0.24.2 → 0.28.2 | **Closed** | 0.x "breaking release": integrity checks for fallback downloads and Go 1.26 toolchain change. | After build output/loader verification on 0.28.x. |
| 12 | `argon2` (cargo) | 0.5.3 → 0.6.0 | **Closed** | Password-hash/`digest` 0.11 migration with API and MSRV changes. | With the coordinated RustCrypto migration. |
| 13 | `android-actions/setup-android` | 3 → 4 | **Closed** | Major action bump; SDK setup behavior may change. | After validating Android CI workflow on v4. |
| 14 | `actions/setup-node` | 4 → 7 | **Closed** | Major action bump; Node version/cache behavior changes. | After validating extension CI workflow on v7. |
| 15 | `jsdom` (extension) | 25.0.1 → 31.0.1 | **Closed** | Breaking: `@napi-rs/canvas` replaces `canvas`, rewritten CSS/parsing, Node ≥22.22.2/24.15/26. | After Node upgrade and test-environment/canvas migration. |
| 16 | `aes-gcm` (cargo) | 0.10.3 → 0.11.1 | **Closed** | RustCrypto `aead`/`digest` 0.11 migration; crypto API changes. | With the coordinated RustCrypto migration. |
| 17 | `actions/upload-artifact` | 4 → 7 | **Closed** | Major action bump; artifact upload/retention behavior changes. | After validating artifact publishing in CI. |
| 18 | `sha2` (cargo) | 0.10.9 → 0.11.1 | **Closed** | `digest` 0.11, newtypes, `compress*` moved to `block_api`, features removed, MSRV 1.88. | With the coordinated RustCrypto migration. |
| 19 | `hmac` (cargo) | 0.12.1 → 0.13.0 | **Closed** | `digest` 0.11; `Mac::new`/`new_from_slice`/`generate_key` removed. | With the coordinated RustCrypto migration. |
| 20 | `dirs` (cargo) | 6.0.0 → 7.0.0 | **Closed** | True semver major; platform path API changes. | After migrating path-resolution call sites. |
| 21 | `base64` (cargo) | 0.22.1 → 0.23.1 | **Closed** | Pre-1.0 breaking release; engine/API changes. | After migrating encoding/decoding call sites. |
| 22 | `rand` (cargo) | 0.8.8 → 0.10.4 | **Closed** | Two breaking generations: `Rng`→`RngExt`, `OsRng`→`SysRng`, renamed sampling fns, removed `ReseedingRng`, MSRV 1.85, reproducibility changes. | Dedicated RNG migration PR with full test/vector review. |
| 23 | `org.jetbrains.kotlin.android` | 2.2.0 → 2.4.21 | **Closed** | 2.x minor but brings language stable-feature changes, min AGP 8.5.2 and Gradle compatibility shifts; part of the Android toolchain jump. | With the combined Gradle 9 + AGP 9 + Kotlin 2.4 migration PR. |
| 24 | `gradle-wrapper` | 8.13 → 9.9.2 | **Closed** | Gradle 9 requires JVM 17+, removes many deprecated APIs and changes configuration/caching behavior. | After working through the Gradle 8→9 upgrade guide and build validation. |

## Dependabot configuration notes

- `version-update:semver-major` ignore rules are used for true majors (GitHub Actions,
  AGP, Gradle wrapper, dirs, TypeScript, Vitest, jsdom).
- Pre-1.0 crates (`sha1`, `argon2`, `aes-gcm`, `sha2`, `hmac`, `rand`, `base64`) and
  `esbuild`/Kotlin 2.4 are classified as *minor* by Dependabot's simplistic SemVer
  handling, so exact `versions` rules hold the closed versions instead.
- Cadence remains `weekly` for every ecosystem. Review/remove these rules when the
  migrations land; target review date **2027-02-26**.

## Primary sources consulted

- TypeScript 7: https://devblogs.microsoft.com/typescript/announcing-typescript-7-0/
- Vitest 5 migration: https://vitest.dev/guide/migration.html
- RustCrypto `sha2` 0.11: https://github.com/RustCrypto/hashes/blob/master/sha2/CHANGELOG.md
- RustCrypto `digest` 0.11: https://github.com/RustCrypto/traits/blob/master/digest/CHANGELOG.md
- rand changelog: https://github.com/rust-random/rand/blob/master/CHANGELOG.md
- Gradle 9 upgrade guide: https://docs.gradle.org/9.0.0/userguide/upgrading_version_8.html
- AGP releases: https://developer.android.com/build/releases/gradle-plugin
- Kotlin 2.4: https://kotlinlang.org/docs/whatsnew24.html
- jsdom releases: https://github.com/jsdom/jsdom/releases
- esbuild 0.28: https://github.com/evanw/esbuild/releases/tag/v0.28.0
- actions/checkout 7: https://github.com/actions/checkout/releases/tag/v7.0.0

Questions: nekomeowpurr@proton.me — https://github.com/thisisforlearn/newpgp
