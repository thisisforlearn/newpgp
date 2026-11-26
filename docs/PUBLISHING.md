# Publishing NewPGP to stores and feeds

Official project: https://github.com/thisisforlearn/newpgp
Release owner: **Vaibhav (@thisisforlearn)** — nekomeowpurr@proton.me
Contributor: **@sudo-linux-neko**

> **Honest note:** the v0.1 series has **not** had an independent security
> audit. Do not describe these builds as audited, verified or production-hardened
> in store listings or announcements. See [SECURITY_AUDIT.md](SECURITY_AUDIT.md).

This guide separates what CI can do today from what requires an account,
credentials and human review. The tag/release mechanics are in
[RELEASING.md](RELEASING.md).

## 1. What is automated today

| Step | Automated? | Where |
|---|---|---|
| Build desktop installers (Windows/macOS/Linux) | Yes | https://github.com/thisisforlearn/newpgp/actions/workflows/desktop-release.yml |
| Build CLI binaries | Yes | https://github.com/thisisforlearn/newpgp/actions/workflows/cli-release.yml |
| Build debug and (if secrets set) signed Android APK | Yes | https://github.com/thisisforlearn/newpgp/actions/workflows/android-apk.yml |
| Package browser extension zip | Yes (when the build emits one) | https://github.com/thisisforlearn/newpgp/actions/workflows/extension.yml |
| Aggregate artifacts into **one GitHub Release** with generated notes | Yes | https://github.com/thisisforlearn/newpgp/actions/workflows/release-all.yml |
| Manual fallback for the same release | Yes (`gh` CLI) | `scripts/release.ps1` |
| SHA-256 checksum manifest | Yes (`SHA256SUMS.txt` release asset) | see [RELEASING.md](RELEASING.md#5-verifying-downloads) |
| Submit to Chrome Web Store / AMO / Edge Add-ons | **No** — credentials + review | manual |
| Submit to F-Droid | **No** — merge request + maintainer review | manual |
| Submit to Google Play | **No** — Play Console + signing + review | manual |
| Code-sign desktop installers / notarize macOS build | **No** — certificates needed | manual/future |
| GPG-sign release assets | **No** — signing key and CI integration needed | manual/future |

Practical rule: our automation ends at the GitHub Release. Everything below is
a manual publishing action performed by the release owner, starting from the
assets downloaded from that release:

```bash
gh release download v0.1.3 --dir newpgp-publish
sha256sum --check newpgp-publish/SHA256SUMS.txt
```

## 2. Chrome Web Store

Official docs: https://developer.chrome.com/docs/webstore/publish
Developer Dashboard: https://chrome.google.com/webstore/devconsole
Review process: https://developer.chrome.com/docs/webstore/review-process

1. Register and set up a developer account, then prepare the extension
   (manifest, icons, single-purpose description, permissions).
2. Upload the packaged `.zip` from the GitHub Release. The documented package
   size limit is 2 GB. New publishers are initially limited to two published
   items; an increase can be requested from the Dashboard.
3. Fill in **Store Listing**, **Privacy** (single purpose and data handling),
   **Distribution** and, if needed, **Test instructions** with any credentials
   reviewers require.
4. Click **Submit for Review**. You can defer publishing after approval; a
   staged submission expires after 30 days and reverts to a draft.
5. Rollback: there is no version rollback button. Upload a fixed package as a
   new version, or unpublish while a correction is reviewed. See
   https://developer.chrome.com/docs/webstore/update and the cancel-review
   flow if a bad submission is still under review.

**Credential need:** Chrome developer account (personal/Google account, with
its own verification). We do not commit store credentials or automate upload.
The store's review is mandatory even if the package is built by CI.

## 3. Firefox Add-ons (AMO)

Official docs: https://extensionworkshop.com/documentation/publish
Submitting: https://extensionworkshop.com/documentation/publish/submitting-an-add-on/
Signing: https://extensionworkshop.com/documentation/publish/signing-and-distribution-overview/
Developer Hub: https://addons.mozilla.org/en-US/developers/

1. Firefox will not install an unsigned extension in a normal release profile.
   The raw zip from `extension.yml` is therefore **not** end-user ready.
2. Either submit through the Developer Hub, or sign locally with `web-ext sign`
   using AMO API credentials (JWT issuer/secret from your account):
   ```bash
   npx web-ext sign --source-dir dist --api-key "$WEB_EXT_API_KEY" --api-secret "$WEB_EXT_API_SECRET"
   ```
   A signed XPI can also be self-distributed outside AMO.
3. Provide source code when requested; AMO's policies cover obfuscated code and
   third-party libraries. See https://extensionworkshop.com/documentation/publish/source-code-submission/
   and the Add-on Policies linked from the publish docs.
4. Submissions go through signing and possible post-submission review. Plan for
   review time before announcing a release.
5. Rollback: AMO supports an eligible version rollback through the Developer
   Hub or the Add-on Submission API —
   https://extensionworkshop.com/documentation/publish/version-rollback/

**Credential need:** AMO account + API credentials for `web-ext sign` (store as
secrets, never in the repo). Signing is a store service; we cannot reproduce it
with a private key in CI.

## 4. Edge Add-ons

Official docs: https://learn.microsoft.com/en-us/microsoft-edge/extensions/publish/publish-extension
Partner Center: https://partner.microsoft.com/dashboard/microsoftedge/public/login?ref=dd
Porting a Chrome extension: https://learn.microsoft.com/en-us/microsoft-edge/extensions/developer-guide/port-chrome-extension

1. Create a Microsoft Edge extension developer account in Partner Center.
2. Upload the `.zip` from the GitHub Release and create the extension entry.
3. Complete Availability (visibility/markets), Properties, Privacy (single
   purpose, per-permission justification, remote code declaration, data usage,
   privacy policy URL) and Store Listings. The docs require a description of at
   least 250 characters and a 1:1 logo (recommended 300x300, minimum 128x128)
   for each language.
4. Submit with certification notes for reviewers (test accounts, steps to
   reach hidden features, what changed). Certification can take up to seven
   business days.
5. Rollback: publish a corrected package as a new version or set visibility to
   **Hidden**; users who installed while public keep updates. Track errors in
   Partner Center.

**Credential need:** Microsoft account enrolled in Partner Center. No
non-interactive submission is configured; Microsoft's certification is manual.

## 5. F-Droid

Official docs: https://f-droid.org/docs/
Quick start: https://f-droid.org/docs/Submitting_to_F-Droid_Quick_Start_Guide/
Build metadata: https://f-droid.org/docs/Build_Metadata_Reference/
Inclusion policy: https://f-droid.org/docs/Inclusion_Policy/
Signing: https://f-droid.org/docs/Signing_Process/

F-Droid **builds from source on its own build servers**. It does not publish the
APK we attach to a GitHub Release (that APK is only useful for reproducible-build
comparison via `Binaries` / `AllowedAPKSigningKeys`).

1. Check the Inclusion Policy: public source repo with a real FOSS license,
   FOSS-only dependencies, FOSS build tooling, and the author's permission.
2. Add upstream metadata to this repository so updates can be detected
   automatically:
   `fastlane/metadata/android/en-US/short_description.txt` (<80 chars),
   `full_description.txt`, `images/icon.png`, `images/phoneScreenshots/`, and
   `changelogs/<versionCode>.txt`. Keep version names/codes in the standard
   Gradle locations and keep release tags (`v*`).
3. Fork `fdroiddata`, add `metadata/<applicationId>.yml` with `RepoType: git`,
   `Repo: https://github.com/thisisforlearn/newpgp`, `License: GPL-3.0-or-later`,
   `SourceCode`, `IssueTracker`, and a `Builds` block with `versionName`,
   `versionCode` and the full commit hash. Use `AutoUpdateMode: Version` and
   `UpdateCheckMode: Tags` so new tags produce update merge requests. Set
   `subdir: android/keyboard` for this repository's Gradle project.
4. Validate with `fdroid lint <appid>` / `fdroid build <appid>` (see the
   quick-start guide), then open a merge request against fdroiddata. Maintainers
   review licensing, dependencies and the build.
5. After the metadata merge, F-Droid's build cycle and signing step typically
   take roughly 24–48 hours before the app appears in the repository.
6. Rollback: disable the affected build in the metadata (with a reason) or use
   `Disabled:` for the whole app; Android's signing-key rule means a key change
   requires a new application ID. See the Build Metadata Reference.

**Credential need:** none from us for the build (F-Droid signs with its own
key), but a GitLab account and maintainer review are required for the merge
request.

## 6. Google Play

Official docs: https://developer.android.com/studio/publish
App signing: https://developer.android.com/studio/publish/app-signing
Upload: https://developer.android.com/studio/publish/upload-bundle
Prepare and roll out: https://support.google.com/googleplay/android-developer/answer/7159011
Play Console: https://play.google.com/console/

1. A Play Console developer account is required. New apps since August 2021
   must use **Play App Signing**: you sign the uploaded AAB/APK with an *upload
   key*, and Google signs the delivered APKs with the *app signing key*.
2. Generate an upload keystore (valid past 22 October 2033, as required by
   Play), keep it offline, and use the four `NEWPGP_*` signing secrets in CI for
   any build that will be uploaded. A **debug APK is not publishable**.
3. Produce a signed Android App Bundle (preferred) or signed APK, upload it,
   and complete the store listing, data-safety declarations and target API
   requirements. Cumulative download size limit is 4 GB.
4. Test through the internal track / internal app sharing, then roll out to
   production gradually. Google review applies before public distribution.
5. Rollback: halt a staged rollout in the Play Console; to truly revert, bump
   the version code and upload a corrected build. Never lose the app signing
   key — without it you cannot update the app. See the app-signing docs for
   key upgrade and upload-key reset.

**Credential need:** Play Console account, upload keystore, Play App Signing
enrolment, and human completion of policy forms. No Play submission is
automated.

## 7. GitHub Releases (fully in scope for CI)

Official docs:
https://docs.github.com/en/repositories/releasing-projects-on-github/managing-releases-in-a-repository
Automatically generated notes:
https://docs.github.com/en/repositories/releasing-projects-on-github/automatically-generated-release-notes
CLI manual: https://cli.github.com/manual/gh_release_create

- Pushing a `v*` tag builds the platform artifacts and `release-all.yml`
  publishes a single release with generated notes and `SHA256SUMS.txt`.
- `scripts/release.ps1 -Tag <tag>` is the idempotent manual fallback.
- Only the publish job gets `contents: write`; other workflows keep
  `contents: read` (except jobs that already publish their own asset).
- If immutable releases are enabled on the repository, create a draft, attach
  all assets, then publish; assets cannot be replaced afterwards.

## 8. Recommended order for a real launch

1. Finish the v0.1 security work and an external review; add a dated audit
   report before making public claims.
2. Set up store accounts and signing material (Android upload key, desktop code
   signing, AMO API credentials).
3. Add the F-Droid metadata and fastlane files to this repository.
4. Cut a GitHub Release from a tag and verify checksums on a clean machine.
5. Submit to AMO and Edge (usually faster review) and Chrome Web Store; prepare
   separate listings for each store.
6. Submit to F-Droid and Google Play last; both have the longest lead times.
   Update [RELEASING.md](RELEASING.md) with the actual store URLs when live.
