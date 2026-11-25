# Build verification for `android/keyboard`

This records the exact commands used by `.github/workflows/android-apk.yml` so the
result can be reproduced locally. There is no committed Gradle wrapper JAR, so the
workflow always uses the `gradle` on PATH after pinning it to 8.13.

## CI commands (debug job)

Runs on `ubuntu-latest` with job-level
`ANDROID_HOME=ANDROID_SDK_ROOT=/usr/local/lib/android/sdk` (the SDK preinstalled on
the runner). `android-actions/setup-android@v3` is deliberately **not** used: it
runs `sdkmanager tools`, a package that no longer exists.

```bash
# 1. SDK: accept licenses, install exactly what this project needs.
SDKMGR="$(command -v sdkmanager || true)"
if [ -z "$SDKMGR" ] && [ -x "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" ]; then
  SDKMGR="$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager"
fi
if [ -z "$SDKMGR" ]; then
  SDKMGR="$(find "$ANDROID_HOME/cmdline-tools" -mindepth 3 -maxdepth 3 -type f -name sdkmanager | sort -V | tail -n 1)"
fi
yes | "$SDKMGR" --licenses > /dev/null 2>&1 || true
"$SDKMGR" "platforms;android-36" "build-tools;36.0.0"

# 2. Build. gradle/actions/setup-gradle@v4 puts Gradle 8.13 on PATH.
cd android/keyboard
if [ -f ./gradlew ]; then
  ./gradlew assembleDebug
else
  gradle assembleDebug
fi
```

Artifact: `android/keyboard/app/build/outputs/apk/debug/*.apk` →
`newpgp-keyboard-debug`, with `if-no-files-found: error`.

## CI commands (release job)

Eligible only on `refs/tags/v*` **and** when all four secrets are set:
`NEWPGP_KEYSTORE_BASE64`, `NEWPGP_KEYSTORE_PASSWORD`, `NEWPGP_KEY_ALIAS`,
`NEWPGP_KEY_PASSWORD`. If any is missing, every real step is skipped and the job
finishes successfully with a notice; no artifact is uploaded and no release is
published. Secrets are turned into a job-level env var (`SIGNING_READY`) because
GitHub does not allow `secrets.*` in an `if:` expression.

```bash
cd android/keyboard
echo "$NEWPGP_KEYSTORE_BASE64" | base64 --decode > app/newpgp-release.jks
ORG_GRADLE_PROJECT_NEWPGP_UPLOAD_STORE_FILE=app/newpgp-release.jks \
ORG_GRADLE_PROJECT_NEWPGP_UPLOAD_STORE_PASSWORD="$NEWPGP_KEYSTORE_PASSWORD" \
ORG_GRADLE_PROJECT_NEWPGP_UPLOAD_KEY_ALIAS="$NEWPGP_KEY_ALIAS" \
ORG_GRADLE_PROJECT_NEWPGP_UPLOAD_KEY_PASSWORD="$NEWPGP_KEY_PASSWORD" \
  gradle assembleRelease
```

`app/build.gradle.kts` reads those properties only when all four are defined; a
plain `gradle assembleDebug` (or `assembleRelease` without them) configures and
builds without any keystore.

## Run locally

With a local Android SDK and JDK 17:

```bash
export ANDROID_HOME=/path/to/Android/Sdk
export ANDROID_SDK_ROOT=$ANDROID_HOME
"$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" "platforms;android-36" "build-tools;36.0.0"
cd android/keyboard
gradle assembleDebug          # or ./gradlew assembleDebug once the wrapper JAR is committed
gradle testDebugUnitTest
```

Verified configuration: JDK 17, Gradle 8.13, AGP 8.11.1, Kotlin 2.2.0,
`compileSdk = 36`, `minSdk = 24`, `buildToolsVersion = "36.0.0"`.

## Validate the workflow YAML

```bash
# From android/keyboard:
python3 -c "import yaml,sys; yaml.safe_load(open(sys.argv[1]))" \
  ../../.github/workflows/android-apk.yml
```
