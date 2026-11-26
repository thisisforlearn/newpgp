#!/usr/bin/env bash
# Register the NewPGP native messaging host on macOS/Linux.
# A Chrome/Edge extension ID is required; the Firefox add-on ID is optional.
# Usage: ./install.sh <chrome-extension-id> [firefox-extension-id] [path-to-newpgp]

set -euo pipefail

CHROME_ID="${1:-}"
FIREFOX_ID="${2:-}"
EXECUTABLE="${3:-$(command -v newpgp || true)}"

if [[ -z "$CHROME_ID" ]]; then
  echo "Error: a Chrome/Edge extension ID is required." >&2
  echo "Usage: $0 <chrome-extension-id> [firefox-extension-id] [path-to-newpgp]" >&2
  exit 1
fi
if [[ ! "$CHROME_ID" =~ ^[a-p]{32}$ ]]; then
  echo "Error: Chrome/Edge extension ID must be 32 lowercase letters a-p (got '$CHROME_ID')." >&2
  exit 1
fi
if [[ -n "$FIREFOX_ID" && ! "$FIREFOX_ID" =~ ^[A-Za-z0-9_.@+{}:-]{1,100}$ ]]; then
  echo "Error: Firefox add-on ID looks invalid (got '$FIREFOX_ID')." >&2
  exit 1
fi
if [[ -z "$EXECUTABLE" ]]; then
  echo "Error: pass the path to the newpgp executable as the third argument." >&2
  exit 1
fi
# Chrome and Firefox require an absolute path to the host binary.
case "$EXECUTABLE" in
  /*) ;;
  *) EXECUTABLE="$(cd "$(dirname "$EXECUTABLE")" && pwd -P)/$(basename "$EXECUTABLE")" ;;
esac
if [[ ! -x "$EXECUTABLE" ]]; then
  echo "Error: newpgp executable not found or not executable: $EXECUTABLE" >&2
  exit 1
fi

HOST="com.newpgp.bridge.json"
case "$(uname -s)" in
  Darwin)
    CHROME_DIR="$HOME/Library/Application Support/Google/Chrome/NativeMessagingHosts"
    EDGE_DIR="$HOME/Library/Application Support/Microsoft Edge/NativeMessagingHosts"
    FIREFOX_DIR="$HOME/Library/Application Support/Mozilla/NativeMessagingHosts"
    ;;
  *)
    CHROME_DIR="$HOME/.config/google-chrome/NativeMessagingHosts"
    EDGE_DIR="$HOME/.config/microsoft-edge/NativeMessagingHosts"
    FIREFOX_DIR="$HOME/.mozilla/native-messaging-hosts"
    ;;
esac

# Build the manifest with a real JSON encoder so backslashes, quotes and
# control characters in paths/IDs are escaped correctly. Include only the
# keys whose IDs were actually supplied.
write_manifest() {
  local dir="$1"
  local target="$dir/$HOST"
  mkdir -p "$dir"

  if command -v python3 >/dev/null 2>&1 || command -v python >/dev/null 2>&1; then
    local py="python3"
    command -v python3 >/dev/null 2>&1 || py="python"
    NEWPGP_PATH="$EXECUTABLE" NEWPGP_CHROME_ID="$CHROME_ID" NEWPGP_FIREFOX_ID="$FIREFOX_ID" \
      "$py" - <<'PY' > "$target"
import json
import os

manifest = {
    "name": "com.newpgp.bridge",
    "description": "NewPGP native messaging bridge (Rust)",
    "path": os.environ["NEWPGP_PATH"],
    "type": "stdio",
}
if os.environ.get("NEWPGP_CHROME_ID"):
    manifest["allowed_origins"] = [
        "chrome-extension://" + os.environ["NEWPGP_CHROME_ID"] + "/"
    ]
if os.environ.get("NEWPGP_FIREFOX_ID"):
    manifest["allowed_extensions"] = [os.environ["NEWPGP_FIREFOX_ID"]]
print(json.dumps(manifest, indent=2))
PY
  elif command -v jq >/dev/null 2>&1; then
    jq -n \
      --arg path "$EXECUTABLE" \
      --arg chrome "$CHROME_ID" \
      --arg firefox "$FIREFOX_ID" \
      '{
         name: "com.newpgp.bridge",
         description: "NewPGP native messaging bridge (Rust)",
         path: $path,
         type: "stdio"
       }
       + (if $chrome != "" then {allowed_origins: ["chrome-extension://\($chrome)/"]} else {} end)
       + (if $firefox != "" then {allowed_extensions: [$firefox]} else {} end)' > "$target"
  else
    echo "Error: need python3 (or python, or jq) to write a valid JSON manifest." >&2
    exit 1
  fi
}

write_manifest "$CHROME_DIR"
write_manifest "$EDGE_DIR"
if [[ -n "$FIREFOX_ID" ]]; then write_manifest "$FIREFOX_DIR"; fi

echo "Registered native host for $EXECUTABLE"
echo "Restart the browser and reload the extension."
