#!/usr/bin/env bash
set -euo pipefail

cd "$(git rev-parse --show-toplevel)"

if [ -z "${RUNNER_TEMP:-}" ]; then
  RUNNER_TEMP="$(mktemp -d)"
fi

work="$RUNNER_TEMP/ios-signed"
mkdir -p "$work"

keychain_path=""
key_path=""
profile_install=""
local_config="iosApp/Configuration/Config.local.xcconfig"
config_backup=""
wrote_local_config=0
previous_default=""
saved_keychains="$work/previous-keychains.txt"

cleanup() {
  status=$?
  if [ -n "$previous_default" ]; then
    security default-keychain -s "$previous_default" >/dev/null 2>&1 || true
  fi
  if [ -f "$saved_keychains" ]; then
    restored=()
    while IFS= read -r line; do
      line="${line#"${line%%[![:space:]]*}"}"
      line="${line%\"}"
      line="${line#\"}"
      if [ -n "$line" ]; then
        restored+=("$line")
      fi
    done < "$saved_keychains"
    if [ "${#restored[@]}" -gt 0 ]; then
      security list-keychains -d user -s "${restored[@]}" >/dev/null 2>&1 || true
    fi
  fi
  if [ -n "$keychain_path" ]; then
    security delete-keychain "$keychain_path" >/dev/null 2>&1 || true
  fi
  if [ -n "$profile_install" ]; then
    rm -f "$profile_install"
  fi
  if [ -n "$key_path" ]; then
    rm -f "$key_path"
  fi
  if [ -n "$config_backup" ]; then
    mv "$config_backup" "$local_config"
  elif [ "$wrote_local_config" = 1 ]; then
    rm -f "$local_config"
  fi
  exit "$status"
}
trap cleanup EXIT

require() {
  if [ -z "${!1:-}" ]; then
    echo "$1 is required" >&2
    exit 1
  fi
}

require APPLE_TEAM_ID
require APPLE_DISTRIBUTION_P12_BASE64
require APPLE_DISTRIBUTION_P12_PASSWORD
require APP_STORE_CONNECT_PROFILE_BASE64

if [[ ! "$APPLE_TEAM_ID" =~ ^[A-Z0-9]{10}$ ]]; then
  echo "APPLE_TEAM_ID must be a 10 character Apple Team ID" >&2
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

version_name="$(python3 -c 'import json; print(json.load(open("update/manifest.json"))["versionName"])')"
version_code="$(python3 -c 'import json; print(json.load(open("update/manifest.json"))["versionCode"])')"
channel="$(python3 -c 'import json; print(json.load(open("update/manifest.json")).get("channel", "stable"))')"
bundle_id="$(python3 -c 'import re, pathlib; text = pathlib.Path("iosApp/Configuration/AppIdentifiers.xcconfig").read_text(); match = re.search(r"^YAMIBO_IOS_BUNDLE_ID=(.+)$", text, re.M); assert match, "YAMIBO_IOS_BUNDLE_ID missing"; print(match.group(1).strip())')"

if [[ ! "$version_code" =~ ^[0-9]+$ ]]; then
  echo "versionCode must be numeric" >&2
  exit 1
fi
if [[ ! "$version_name" =~ ^[0-9A-Za-z][0-9A-Za-z.+-]*$ ]]; then
  echo "versionName contains unsupported characters" >&2
  exit 1
fi
if [[ ! "$channel" =~ ^[A-Za-z0-9][A-Za-z0-9._-]*$ ]]; then
  echo "channel contains unsupported characters" >&2
  exit 1
fi
if [[ ! "$bundle_id" =~ ^[A-Za-z0-9][A-Za-z0-9.-]+$ ]]; then
  echo "iOS bundle id is invalid: $bundle_id" >&2
  exit 1
fi

echo "Signing ${bundle_id} ${channel} ${version_name} (${version_code})"

p12_path="$work/distribution.p12"
profile_path="$work/profile.mobileprovision"
printf '%s' "$APPLE_DISTRIBUTION_P12_BASE64" | tr -d '[:space:]' | base64 -d > "$p12_path"
printf '%s' "$APP_STORE_CONNECT_PROFILE_BASE64" | tr -d '[:space:]' | base64 -d > "$profile_path"
chmod 600 "$p12_path" "$profile_path"

security cms -D -i "$profile_path" > "$work/profile.plist"
PROFILE_PLIST="$work/profile.plist" \
P12_PATH="$p12_path" \
PROFILE_META="$work/profile.json" \
EXPECTED_BUNDLE_ID="$bundle_id" \
python3 - <<'PY'
import base64
import json
import os
import plistlib
import re
import subprocess
from datetime import datetime, timezone
from pathlib import Path

profile = plistlib.loads(Path(os.environ["PROFILE_PLIST"]).read_bytes())
entitlements = profile.get("Entitlements") or {}
team_id = os.environ["APPLE_TEAM_ID"]
bundle_id = os.environ["EXPECTED_BUNDLE_ID"]
expected_app_id = f"{team_id}.{bundle_id}"

def fail(message):
    raise SystemExit(message)

if entitlements.get("get-task-allow") is True:
    fail("Provisioning profile allows debugging. Use an App Store Connect profile.")
if entitlements.get("beta-reports-active") is not True:
    fail("Provisioning profile is missing beta-reports-active. TestFlight needs an App Store Connect profile.")
if "ProvisionedDevices" in profile or profile.get("ProvisionsAllDevices") is True:
    fail("Provisioning profile is Ad Hoc, development, or enterprise, not App Store Connect.")
teams = profile.get("TeamIdentifier") or []
if team_id not in teams:
    fail("Provisioning profile team does not match APPLE_TEAM_ID.")
if entitlements.get("application-identifier") != expected_app_id:
    fail(
        "Provisioning profile application-identifier is "
        f"{entitlements.get('application-identifier')}, expected {expected_app_id}."
    )
expiration = profile.get("ExpirationDate")
if expiration is None:
    fail("Provisioning profile has no expiration date.")
if expiration.tzinfo is None:
    expiration = expiration.replace(tzinfo=timezone.utc)
if expiration <= datetime.now(timezone.utc):
    fail(f"Provisioning profile expired at {expiration.isoformat()}.")
name = str(profile.get("Name") or "").strip()
uuid = str(profile.get("UUID") or "").strip()
if not name or not uuid:
    fail("Provisioning profile is missing Name or UUID.")

def read_p12_certs():
    p12_path = os.environ["P12_PATH"]
    attempts = []
    if os.path.isfile("/usr/bin/openssl"):
        attempts.append([
            "/usr/bin/openssl", "pkcs12", "-in", p12_path,
            "-nokeys", "-clcerts", "-passin", "env:APPLE_DISTRIBUTION_P12_PASSWORD",
        ])
    attempts.append([
        "openssl", "pkcs12", "-legacy", "-in", p12_path,
        "-nokeys", "-clcerts", "-passin", "env:APPLE_DISTRIBUTION_P12_PASSWORD",
    ])
    errors = []
    for command in attempts:
        result = subprocess.run(command, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        if result.returncode == 0:
            return result.stdout
        errors.append(result.stderr.decode(errors="replace").strip())
    fail("Could not read the distribution p12. " + " | ".join(error for error in errors if error))

pem = read_p12_certs()
leafs = [
    base64.b64decode(re.sub(r"\s+", "", body))
    for body in re.findall(
        r"-----BEGIN CERTIFICATE-----(.*?)-----END CERTIFICATE-----",
        pem.decode(),
        re.S,
    )
]
if not leafs:
    fail("Distribution p12 does not contain a certificate.")
allowed = {bytes(cert) for cert in profile.get("DeveloperCertificates") or []}
if not any(cert in allowed for cert in leafs):
    fail("Provisioning profile does not include the certificate from the distribution p12.")

Path(os.environ["PROFILE_META"]).write_text(
    json.dumps({"name": name, "uuid": uuid}),
    encoding="utf-8",
)
print(f"Profile {name} ({uuid}) expires {expiration.date().isoformat()}")
PY

profile_name="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["name"])' "$work/profile.json")"
profile_uuid="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["uuid"])' "$work/profile.json")"
profile_install="$HOME/Library/MobileDevice/Provisioning Profiles/${profile_uuid}.mobileprovision"
mkdir -p "$HOME/Library/MobileDevice/Provisioning Profiles"
cp "$profile_path" "$profile_install"

security list-keychains -d user > "$saved_keychains"
previous_default="$(security default-keychain -d user | sed -e 's/^[[:space:]]*"//' -e 's/"$//')"
keychain_path="$work/signing.keychain-db"
keychain_password="$(uuidgen)"
security create-keychain -p "$keychain_password" "$keychain_path"
security set-keychain-settings -lut 21600 "$keychain_path"
security unlock-keychain -p "$keychain_password" "$keychain_path"
security import "$p12_path" -P "$APPLE_DISTRIBUTION_P12_PASSWORD" -A -t cert -f pkcs12 -k "$keychain_path"
security set-key-partition-list -S apple-tool:,apple:,codesign: -s -k "$keychain_password" "$keychain_path"
security list-keychains -d user -s "$keychain_path"
security default-keychain -s "$keychain_path"

identity="$(security find-identity -v -p codesigning "$keychain_path" | sed -n 's/.*"\(Apple Distribution:.*\)"/\1/p')"
identity="${identity%%$'\n'*}"
if [ -z "$identity" ]; then
  echo "Imported p12 has no Apple Distribution identity" >&2
  exit 1
fi
case "$identity" in
  *"($APPLE_TEAM_ID)") ;;
  *)
    echo "Distribution identity does not match APPLE_TEAM_ID: $identity" >&2
    exit 1
    ;;
esac
echo "Using identity: $identity"

if [ -f "$local_config" ]; then
  config_backup="$(mktemp)"
  cp "$local_config" "$config_backup"
fi
printf 'TEAM_ID = %s\n' "$APPLE_TEAM_ID" > "$local_config"
wrote_local_config=1

chmod +x gradlew
archive_path="$work/yamibo.xcarchive"
xcodebuild \
  -project iosApp/iosApp.xcodeproj \
  -scheme iosApp \
  -configuration Release \
  -destination 'generic/platform=iOS' \
  -archivePath "$archive_path" \
  -derivedDataPath "$work/DerivedData" \
  DEVELOPMENT_TEAM="$APPLE_TEAM_ID" \
  CODE_SIGN_STYLE=Manual \
  "CODE_SIGN_IDENTITY=$identity" \
  "PROVISIONING_PROFILE_SPECIFIER=$profile_name" \
  "OTHER_CODE_SIGN_FLAGS=--keychain $keychain_path" \
  MARKETING_VERSION="$version_name" \
  CURRENT_PROJECT_VERSION="$version_code" \
  archive

EXPORT_PLIST="$work/ExportOptions.plist" \
PROFILE_NAME="$profile_name" \
EXPECTED_BUNDLE_ID="$bundle_id" \
python3 - <<'PY'
import os
import plistlib
from pathlib import Path

plist = {
    "destination": "export",
    "manageAppVersionAndBuildNumber": False,
    "method": "app-store-connect",
    "signingStyle": "manual",
    "teamID": os.environ["APPLE_TEAM_ID"],
    "uploadSymbols": True,
    "provisioningProfiles": {
        os.environ["EXPECTED_BUNDLE_ID"]: os.environ["PROFILE_NAME"],
    },
}
with Path(os.environ["EXPORT_PLIST"]).open("wb") as handle:
    plistlib.dump(plist, handle, fmt=plistlib.FMT_XML)
PY
plutil -lint "$work/ExportOptions.plist"

xcodebuild \
  -exportArchive \
  -archivePath "$archive_path" \
  -exportPath "$work/export" \
  -exportOptionsPlist "$work/ExportOptions.plist"

ipa_count="$(find "$work/export" -maxdepth 1 -name '*.ipa' -type f | wc -l | tr -d ' ')"
if [ "$ipa_count" != 1 ]; then
  echo "Expected one exported IPA, found $ipa_count" >&2
  exit 1
fi
ipa="$work/yamibo-${channel}-v${version_name}-signed.ipa"
cp "$(find "$work/export" -maxdepth 1 -name '*.ipa' -type f)" "$ipa"

inspect="$work/inspect"
mkdir -p "$inspect"
ditto -x -k "$ipa" "$inspect"
app="$(find "$inspect/Payload" -maxdepth 1 -name '*.app' -type d)"
if [ ! -d "$app" ]; then
  echo "Exported IPA has no app bundle" >&2
  exit 1
fi

signature="$(codesign -dv --verbose=4 "$app" 2>&1 || true)"
printf '%s\n' "$signature"
if [[ "$signature" != *"Authority=Apple Distribution:"* ]]; then
  echo "Exported app is not signed by Apple Distribution" >&2
  exit 1
fi

test "$(/usr/libexec/PlistBuddy -c 'Print :CFBundleIdentifier' "$app/Info.plist")" = "$bundle_id"
test "$(/usr/libexec/PlistBuddy -c 'Print :CFBundleShortVersionString' "$app/Info.plist")" = "$version_name"
test "$(/usr/libexec/PlistBuddy -c 'Print :CFBundleVersion' "$app/Info.plist")" = "$version_code"
security cms -D -i "$app/embedded.mobileprovision" > "$work/embedded.plist"
test "$(plutil -extract UUID raw -o - "$work/embedded.plist")" = "$profile_uuid"
test "$(plutil -extract Entitlements.beta-reports-active raw -o - "$work/embedded.plist")" = "true"
test "$(plutil -extract Entitlements.application-identifier raw -o - "$work/embedded.plist")" = "${APPLE_TEAM_ID}.${bundle_id}"
if plutil -extract Entitlements.get-task-allow raw -o - "$work/embedded.plist" >/dev/null 2>&1; then
  test "$(plutil -extract Entitlements.get-task-allow raw -o - "$work/embedded.plist")" != "true"
fi
if plutil -extract ProvisionedDevices raw -o - "$work/embedded.plist" >/dev/null 2>&1 \
  || plutil -extract ProvisionsAllDevices raw -o - "$work/embedded.plist" >/dev/null 2>&1; then
  echo "Exported app is not signed with an App Store Connect profile" >&2
  exit 1
fi

shasum -a 256 "$ipa"
echo "Signed IPA: $ipa"
