#!/usr/bin/env bash
set -euo pipefail

cd "$(git rev-parse --show-toplevel)"

ipa="${1:-}"
if [ -z "$ipa" ] || [ ! -s "$ipa" ]; then
  echo "Usage: upload-app-store-ipa.sh <signed.ipa>" >&2
  exit 1
fi

require() {
  if [ -z "${!1:-}" ]; then
    echo "$1 is required" >&2
    exit 1
  fi
}

require APP_STORE_CONNECT_KEY_ID
require APP_STORE_CONNECT_ISSUER_ID
require APP_STORE_CONNECT_API_KEY

if [[ ! "$APP_STORE_CONNECT_KEY_ID" =~ ^[A-Z0-9]{10}$ ]]; then
  echo "APP_STORE_CONNECT_KEY_ID must be a 10 character key id" >&2
  exit 1
fi
if [[ ! "$APP_STORE_CONNECT_ISSUER_ID" =~ ^[0-9a-fA-F]{8}-([0-9a-fA-F]{4}-){3}[0-9a-fA-F]{12}$ ]]; then
  echo "APP_STORE_CONNECT_ISSUER_ID must be a UUID" >&2
  exit 1
fi

xcode_app="/Applications/Xcode_26.6.app"
if [ ! -d "$xcode_app" ]; then
  echo "Required Xcode is missing: $xcode_app" >&2
  ls -d /Applications/Xcode*.app >&2 || true
  exit 1
fi
sudo xcode-select -s "$xcode_app"
xcodebuild -version

key_path=""
cleanup() {
  status=$?
  if [ -n "$key_path" ]; then
    rm -f "$key_path"
  fi
  exit "$status"
}
trap cleanup EXIT

umask 077
key_dir="$HOME/.appstoreconnect/private_keys"
mkdir -p "$key_dir"
key_path="$key_dir/AuthKey_${APP_STORE_CONNECT_KEY_ID}.p8"
if printf '%s' "$APP_STORE_CONNECT_API_KEY" | grep -q -- '-----BEGIN '; then
  printf '%s\n' "$APP_STORE_CONNECT_API_KEY" > "$key_path"
else
  printf '%s' "$APP_STORE_CONNECT_API_KEY" | tr -d '[:space:]' | base64 -d > "$key_path"
fi
umask 022
chmod 600 "$key_path"
if ! grep -q -- '-----BEGIN PRIVATE KEY-----' "$key_path"; then
  echo "APP_STORE_CONNECT_API_KEY is not an App Store Connect .p8 private key" >&2
  exit 1
fi

xcrun altool --validate-app -f "$ipa" -t ios \
  --apiKey "$APP_STORE_CONNECT_KEY_ID" \
  --apiIssuer "$APP_STORE_CONNECT_ISSUER_ID"
xcrun altool --upload-app -f "$ipa" -t ios \
  --apiKey "$APP_STORE_CONNECT_KEY_ID" \
  --apiIssuer "$APP_STORE_CONNECT_ISSUER_ID"
echo "Uploaded $ipa to App Store Connect"
