# Releasing NewPGP

Official project: https://github.com/thisisforlearn/newpgp
Maintainer and release owner: **Vaibhav (@thisisforlearn)** — nekomeowpurr@proton.me
Contributor: **@sudo-linux-neko**

This document is the checklist for cutting a GitHub Release. Publishing to
external stores (Chrome Web Store, AMO, Edge, F-Droid, Google Play) is covered
separately in [PUBLISHING.md](PUBLISHING.md).

> **Honest status:** the v0.1 series has **not** received an independent
> security audit. Treat v0.1.x releases as development/preview builds. See
> [SECURITY_AUDIT.md](SECURITY_AUDIT.md) and [SECURITY.md](../SECURITY.md).

## 1. Workflows and artifact names

| Workflow | Link | Tag trigger? | Artifacts consumed by the unified release |
|---|---|---|---|
| Rust CI | https://github.com/thisisforlearn/newpgp/actions/workflows/rust.yml | yes — enforced tag gate | none — fmt, clippy, tests on 3 OS, audit and cargo-deny must be green for the tagged commit |
| Desktop Release | https://github.com/thisisforlearn/newpgp/actions/workflows/desktop-release.yml | yes | `newpgp-desktop-windows`, `newpgp-desktop-macos`, `newpgp-desktop-linux` (`.msi`, `.exe`, `.dmg`, `.app.tar.gz`, `.deb`, `.AppImage*`) |
| CLI Binaries | https://github.com/thisisforlearn/newpgp/actions/workflows/cli-release.yml | yes | `newpgp-cli-windows`, `newpgp-cli-macos`, `newpgp-cli-linux` (`newpgp.exe` / `newpgp`) |
| Android APK | https://github.com/thisisforlearn/newpgp/actions/workflows/android-apk.yml | yes | `newpgp-keyboard-debug`, `newpgp-android-app-debug` (once available), `newpgp-keyboard-release` (only when signing secrets are set) |
| Browser Extension | https://github.com/thisisforlearn/newpgp/actions/workflows/extension.yml | **no** (main pushes and PRs only) | `newpgp-browser-extension` (`.zip` / `.crx` / `.xpi`, when the build produces one) |
| Docs | https://github.com/thisisforlearn/newpgp/actions/workflows/docs.yml | no (main/PR) | none — link check must be green |
| **Publish unified release** | https://github.com/thisisforlearn/newpgp/actions/workflows/release-all.yml | via `workflow_run` | creates/updates **one** release for the tag |

Releases page: https://github.com/thisisforlearn/newpgp/releases

The publish job waits for the tag-triggered workflows to finish, downloads each
source run's artifacts, renames ambiguous files (`newpgp`, `app-debug.apk`) to
unique asset names, writes `SHA256SUMS.txt`, and calls
`softprops/action-gh-release@v3` with `generate_release_notes: true`.

Publication is now gated: the tagged commit must have a successful Rust CI run
(fmt, clippy, tests on Ubuntu/Windows/macOS, `cargo audit` and `cargo-deny`)
plus successful, artifact-producing runs for Desktop Release, CLI Binaries and
Android APK. A failed or missing Rust CI run for the tag blocks publication,
even if all three builds succeeded. Only the optional Browser Extension
artifact may be absent (that is a warning), because its workflow is not
tag-triggered.

## 2. Pre-release checklist

- [ ] All CI checks are green on `main`: `rust.yml`, `android-apk.yml`,
      `desktop-release.yml`, `cli-release.yml`, `extension.yml`, `docs.yml`.
      See [CI_HEALTH.md](CI_HEALTH.md).
- [ ] `cargo fmt --all -- --check`, `cargo clippy --workspace --all-targets -- -D warnings`,
      `cargo test --workspace`, `cargo test -p newpgp-cli --test e2e` and
      `npm test` (in `extensions/browser`) pass locally. See [E2E_TESTING.md](E2E_TESTING.md).
- [ ] Decide the version and confirm no existing tag with that name:
      `git tag --list 'v*' --sort=-version:refname`
- [ ] For a signed Android APK, confirm the repository secrets
      `NEWPGP_KEYSTORE_BASE64`, `NEWPGP_KEYSTORE_PASSWORD`, `NEWPGP_KEY_ALIAS`,
      `NEWPGP_KEY_PASSWORD` are configured. If they are not, the signed release
      job is intentionally skipped and only debug APKs are published.
- [ ] Confirm the Browser Extension workflow has a recent successful run on
      `main` with a packaged zip. It is **not** triggered by tags, so its
      artifact may come from an earlier commit than the tag.
- [ ] Confirm the repository has not enabled *immutable releases* if you plan
      to replace assets in an existing release (GitHub then blocks asset
      replacement — see the rollback section).
- [ ] Announce the planned tag to contributors.

## 3. Cutting the release

1. Commit and verify the intended commit on `main`:
   ```bash
   git checkout main && git pull --ff-only
   git log -1 --oneline
   ```
2. Create and push an annotated tag (replace `v0.1.3` with the chosen version):
   ```bash
   git tag -a v0.1.3 -m "NewPGP v0.1.3"
   git push origin v0.1.3
   ```
   **Do not create the GitHub Release manually.** The tag push runs
   `rust.yml` (fmt, clippy, tests on 3 OS, audit, cargo-deny) as well as
   `desktop-release.yml`, `cli-release.yml` and `android-apk.yml`. Each build
   completion triggers `release-all.yml`, which will not publish unless Rust
   CI is green for the tagged commit.
3. Watch the runs:
   - https://github.com/thisisforlearn/newpgp/actions/workflows/release-all.yml
   - https://github.com/thisisforlearn/newpgp/actions/workflows/desktop-release.yml
   - https://github.com/thisisforlearn/newpgp/actions/workflows/cli-release.yml
   - https://github.com/thisisforlearn/newpgp/actions/workflows/android-apk.yml
4. The publish job may run more than once (once per source workflow
   completion). This is safe: a `concurrency` group serialises the runs and
   `overwrite_files: true` makes a repeat update the same release.
5. Open https://github.com/thisisforlearn/newpgp/releases and check that the
   release notes were generated and that the expected assets are attached.
6. Spot-check at least one binary per platform (installer launches, CLI
   `--version`, APK installs, extension loads in a browser).
7. Announce the release with the release URL and this caveat: v0.1 is not
   audited.

## 4. Manual fallback (gh CLI)

Use this when the automated aggregate job did not run or missed artifacts. It
is idempotent and prints `PASS`/`FAIL` lines plus a final tally. It needs
`gh auth login` with `repo` scope.

```powershell
# Default tag = latest release tag (falls back to newest local git tag)
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\release.ps1

# Explicit tag
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\release.ps1 -Tag v0.1.3
```

The script finds the newest successful run **with artifacts** for each of
`desktop-release.yml`, `cli-release.yml`, `android-apk.yml` and `extension.yml`,
downloads them into an isolated temp folder, normalises names, writes
`SHA256SUMS.txt`, runs `gh release create <tag> --generate-notes` (only if the
release does not exist yet) and `gh release upload <tag> --clobber`, then removes
the temp folder.

## 5. Verifying downloads

### Checksums

`SHA256SUMS.txt` is attached to every release. Its entries use flat asset
filenames, so verification works after a flat download:

```bash
gh release download v0.1.3 --dir newpgp-verify
cd newpgp-verify
sha256sum --check SHA256SUMS.txt
```

On Windows PowerShell:

```powershell
gh release download v0.1.3 --dir newpgp-verify
Get-Content newpgp-verify\SHA256SUMS.txt | ForEach-Object {
  $hash, $name = $_ -split '\s+', 2
  $actual = (Get-FileHash -LiteralPath (Join-Path 'newpgp-verify' $name) -Algorithm SHA256).Hash.ToLowerInvariant()
  if ($actual -eq $hash) { "PASS  $name" } else { "FAIL  $name" }
}
```

### Signatures — what exists and what does not

- **Currently produced by CI:** SHA-256 checksums only. Do **not** claim that
  release assets are GPG-signed, Authenticode-signed, notarized or
  Firefox-signed — none of that is configured for v0.1.
- **Android:** the signed release APK (`newpgp-keyboard-release`) is signed with
  the upload keystore when the four secrets exist; the debug APK is signed with
  the SDK debug key. Verify with:
  ```bash
  apksigner verify --verbose newpgp-keyboard-release-app-release.apk
  keytool -printcert -jarfile newpgp-keyboard-release-app-release.apk
  ```
  Compare the certificate fingerprint with the one you keep offline.
- **If/when detached PGP signatures are published** (`*.asc` next to an asset),
  verify with:
  ```bash
  gpg --recv-keys <fingerprint>
  gpg --verify newpgp-cli-linux.asc newpgp-linux
  ```
  Only trust a fingerprint published through an independent channel (the
  project README/SECURITY.md or a key server), not just the release page.
- **Windows/macOS desktop installers** currently have no code signature or
  notarization. Users will see OS warnings; document this honestly in support
  answers rather than asking users to disable protections permanently.
- **Firefox extension** must be signed by AMO before it can be installed in a
  release Firefox — the raw zip from CI is not installable there. See
  [PUBLISHING.md](PUBLISHING.md).

## 6. Rollback

### Roll back a GitHub release (keeper of the tag)

1. Remove the published release (assets are removed with it):
   ```bash
   gh release delete v0.1.3 --yes --repo thisisforlearn/newpgp
   ```
2. Remove the tag locally and remotely if the tag itself was wrong:
   ```bash
   git tag -d v0.1.3
   git push origin :refs/tags/v0.1.3
   ```
   If only the assets were wrong, keep the tag and re-run
   `scripts/release.ps1 -Tag v0.1.3` (uses `--clobber`) or re-push the tag to
   re-trigger the aggregate workflow.
3. Mark the release as superseded in the release notes of the next good version
   and announce the rollback with the reason.
4. If immutable releases are enabled, asset replacement/deletion and tag
   deletion may be blocked; create a new patch tag instead. Official reference:
   https://docs.github.com/en/repositories/releasing-projects-on-github/managing-releases-in-a-repository

### Roll back a store submission

Store rollbacks are manual and have different rules per store (AMO supports a
version rollback; Google Play lets you halt a rollout; Chrome/Edge require a new
upload or unpublishing). See the rollback notes in [PUBLISHING.md](PUBLISHING.md).
Do not delete a tag that is already the source of a published store build
without coordinating with the store listing.

## 7. Ownership and escalation

- **Vaibhav (@thisisforlearn)** owns tags, GitHub Releases and all store
  accounts. Contact: nekomeowpurr@proton.me.
- **@sudo-linux-neko** contributes release engineering and CI fixes; changes
  to workflows must be reviewed by the owner before a tag is pushed.
- If a release cannot be verified (missing signed APK, zero artifacts, red CI),
  stop and report in an issue instead of publishing an unverified asset set.

## 8. Known limitations of the current automation

- The aggregate workflow uses `softprops/action-gh-release@v3` (Node 24),
  which replaced the deprecated Node 20-based release action. The bump does
  not change the release flow.
- Some jobs use `if-no-files-found: ignore` and can finish green with **zero**
  artifacts. Both the workflow and `scripts/release.ps1` therefore select the
  newest successful run that actually has artifacts and warn when none exists.
- The extension package comes from the latest successful `main` run, not from
  the tagged commit. Re-run `extension.yml` on `main` immediately before tagging
  if the package must match the tag exactly.
- `download-artifact@v4` has no `if-no-files-found` input (that belongs to
  `upload-artifact`); missing artifacts are handled with `pattern` matching,
  `continue-on-error` and explicit warnings.
