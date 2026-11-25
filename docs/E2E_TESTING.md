# End-to-end testing for NewPGP CLI

These tests drive the **real compiled `newpgp` binary** as a subprocess. They do
not call library functions directly: every key generation, import, encryption,
backup and native-messaging exchange goes through the command line or the
framed bridge protocol, exactly as a user or the browser extension would.

Tests create their own unique `NEWPGP_HOME` directories under the system temp
dir and delete them afterwards. No global CLI state is touched.

## Prerequisites

- Rust 1.88+ with Cargo (workspace `rust-version = "1.88"`).
- Windows: PowerShell 5.1 or PowerShell 7 for the script variant.
- No other dev tools are needed (no Node, no test runner, no extra crates).

If your environment has a narrow temp folder or a shared npm cache that causes
tool failures, point them at directories inside the checkout first, e.g.:

```powershell
$env:TEMP = 'D:\pgp-better\.tmp'
$env:TMP  = 'D:\pgp-better\.tmp'
$env:npm_config_cache = 'D:\pgp-better\.npm-cache'
```

## Rust integration tests

From the repository root:

```powershell
cargo test -p newpgp-cli --test e2e
```

Useful variants:

```powershell
cargo test -p newpgp-cli --test e2e -- --nocapture   # show any command output
```

The test file is `crates/newpgp-cli/tests/e2e.rs`. It uses
`env!("CARGO_BIN_EXE_newpgp")` so it always exercises the binary built by the
same Cargo invocation. `tempfile` is *not* a dev-dependency of `newpgp-cli`, so
the tests create unique directories with a pid + nanos + sequence name and
remove them with a `Drop` guard; no manifest changes were needed.

## PowerShell smoke test

From the repository root:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\e2e.ps1
```

The script:

1. Locates Cargo (`cargo` on `PATH`, falling back to `rustup which cargo`).
2. Runs `cargo build -p newpgp-cli`.
3. Creates a fresh temp home per step and runs the same flows as the Rust tests.
4. Prints `PASS: <step>` or `FAIL: <step> -- <reason>` for every step.
5. Removes all temp homes and exits `0` on success, `1` on any failure.

It uses only the compiled `target\debug\newpgp.exe`, PowerShell built-ins and
.NET (`System.Diagnostics.Process`) for the native bridge framing.

## What each test proves

| Test / step | What it proves |
| --- | --- |
| `init_creates_identity_and_prints_fingerprint` | `init` actually creates `identity.asc` and `contacts.json`, and the printed 40-hex fingerprint belongs to the key that `key show` later loads. |
| `key_show_and_list_work_with_a_real_imported_key` | `key show` exports a usable armored public key; feeding that armor back through `key import` makes `key list` show the contact with the right fingerprint. |
| `key_invite_produces_an_https_link_with_a_fragment` | Invite links use `https://`, put data after `#` (never in a query string), and carry address, fingerprint and the public key. |
| `compose_both_and_pgp_only_modes_behave_differently` | With a real recipient key, `--mode both` keeps the typed plaintext **and** appends a `BEGIN PGP MESSAGE` block, `--mode pgp-only` omits the plaintext entirely, and `--mode plain` emits no PGP at all. |
| `encrypt_then_decrypt_roundtrips_a_binary_file_between_two_homes` | Two identities in separate homes exchange keys through `key show`/`key import`; a 512-byte payload containing NULs and invalid UTF-8 encrypts to the recipient's email and decrypts byte-for-byte in the recipient's home. `--sign` plus an imported sender key yields `Signature: Verified`. |
| `backup_create_prints_a_9x4_code_and_restore_recovers_the_key` | `backup create` writes an encrypted PGP message and prints an Autocrypt-style 9-groups-of-4-digits code; `backup restore` with that code recovers the secret key byte-for-byte. |
| `native_bridge_answers_framed_requests_and_exits_on_stdin_close` | Spawning `newpgp bridge`, writing 4-byte little-endian framed JSON, and reading framed responses works for `ping`, `generateKey` and `getKey`; `generateKey` persists a usable key, `getKey` returns its fingerprint and safety number, and closing stdin makes the host exit `0`. |
| `decrypt_with_the_wrong_home_fails_with_a_friendly_hint` | Decrypting with an unrelated identity exits non-zero, prints a plain-language message plus an actionable `Hint:` on stderr, and does not write a plaintext output file. |

All nine Rust tests run and pass; the PowerShell smoke test also passes.

## Product issues these tests caught and that were fixed

1. **`bridge generateKey` + immediate `getKey` by email returned `NO_KEY`.**
   `generateKey` stored the new key only via `KeyRing::set_own_identity`
   (empty-email entry), while `getKey` looks up contacts by email. The bridge
   now also learns the key under the owner's email. The previously ignored test
   `bridge_get_key_immediately_after_generate_key` is enabled and passes.
2. **Fingerprint formatting was inconsistent between commands.** All CLI
   commands now print the same spaced, human-readable fingerprint form.

Keep these tests in CI so both regressions are caught again.
