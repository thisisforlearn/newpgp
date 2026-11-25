#!/usr/bin/env bash
# Register the NewPGP native messaging host on macOS/Linux.
# Usage: ./install.sh <chrome-extension-id> [firefox-extension-id] [path-to-newpgp]

set -euo pipefail

CHROME_ID="${1:-}"
FIREFOX_ID="${2:-}"
EXECUTABLE="${3:-$(command -v newpgp || true)}"

if [[ -z "$EXECUTABLE" ]]; then
  echo "Pass the path to the newpgp executable as the third argument." >&2
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

write_manifest() {
  local dir="$1"
  mkdir -p "$dir"
  cat > "$dir/$HOST" <<EOF
{
  "name": "com.newpgp.bridge",
  "description": "NewPGP native messaging bridge (Rust)",
  "path": "$EXECUTABLE",
  "type": "stdio",
  "allowed_origins": ["chrome-extension://$CHROME_ID/"],
  "allowed_extensions": ["$FIREFOX_ID"]
}
EOF
}

write_manifest "$CHROME_DIR"
write_manifest "$EDGE_DIR"
if [[ -n "$FIREFOX_ID" ]]; then write_manifest "$FIREFOX_DIR"; fi

echo "Registered native host for $EXECUTABLE"
echo "Restart the browser and reload the extension."
