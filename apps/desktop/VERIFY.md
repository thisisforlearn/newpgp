# Desktop build verification

## What was broken

GitHub Actions run `45689139743`, job `Build (linux)` (workflow
`.github/workflows/desktop-release.yml`) failed with 53 compile errors in
`newpgp-desktop`.

Root cause: `src-tauri/src/main.rs` imported `newpgp_core::Result`, a
**one-parameter** alias (`pub type Result<T> = std::result::Result<T, Error>`),
but every `#[tauri::command]` declared `Result<Value, String>` or
`Result<String, String>`. That produced E0107 on each command, followed by
cascading E0277 (`?` could not convert to `newpgp_core::Error`) and E0599
(`newpgp_core::Error` does not implement `Into<InvokeError>` /
`Result<T, newpgp_core::Error>` is not an `IpcResponse`).

## What was fixed

- Removed `use newpgp_core::Result;` from `src-tauri/src/main.rs` so commands
  use the standard two-parameter `std::result::Result<T, String>` that Tauri can
  serialize.
- Added a private `CoreResult<T> = std::result::Result<T, newpgp_core::Error>`
  alias for the internal helpers (`data_dir`, `load_secret`, `keyring`) and for
  the recipient-collection `collect()` in `encrypt_message`.
- Kept all errors user-friendly: every `newpgp_core::Error` is converted with
  the existing `friendly()` helper, including public-key export (which now uses
  an explicit `ArmorOptions::default()`).
- All eight commands are unchanged in name and signature shape:
  `create_identity`, `my_key`, `import_key`, `contacts`, `verify_contact`,
  `encrypt_message`, `decrypt_message`, `compose_message`. The
  `tauri::generate_handler![...]` list and the `invoke(...)` names in `src/app.js`
  were not changed.

## Reproduce locally

The Rust backend is its own Cargo workspace, so check it directly:

```powershell
cd apps/desktop/src-tauri
cargo check
```

If the default cargo registry cache is broken, use a fresh cache on another
drive (all of these directories must exist first):

```powershell
$env:CARGO_HOME = 'D:\pgp-better\.cargo-home2'
$env:CARGO_TARGET_DIR = 'D:\pgp-better\.desktop-target2'
$env:TEMP = 'D:\pgp-better\.tmp'
$env:TMP = 'D:\pgp-better\.tmp'
cargo check -j 1
```

Full release build (same as CI; on Linux install the Tauri system prerequisites
listed in the workflow first):

```powershell
cd apps/desktop
npm ci
npm run tauri build
```

## Validation performed

- `cargo check -j 1` from `apps/desktop/src-tauri` with the fresh D: caches:
  **passed** (`Finished dev profile`, exit code 0). Only warnings are the
  pre-existing unused dependencies `newpgp-backup` and `newpgp-search` declared
  in `Cargo.toml`; they are harmless and were left untouched.
- `src-tauri/tauri.conf.json` and `package.json` both parse as valid JSON
  (checked with Python's `json.load`).
- Frontend `invoke()` names checked against `generate_handler!`: all seven
  frontend calls map to backend commands; `encrypt_message` remains registered
  for callers that use it.

## Remaining risk

- The full `npm run tauri build` (bundling: `.deb`/AppImage/MSI/DMG) was not run
  here; only the Rust compile was verified. CI also installs Linux WebKitGTK /
  appindicator system libraries that are not present on this machine.
- `tauri.conf.json` declares no capabilities directory. Tauri v2 builds without
  one (custom app commands do not need ACL permissions), but if a future change
  requires core/plugin commands, a capability file will need to be added.
- The two unused-dependency warnings in `src-tauri/Cargo.toml` are pre-existing
  and out of scope for this fix.
