#!/usr/bin/env bash
set -euo pipefail

cd "$(git rev-parse --show-toplevel)"
version_name="$(python3 -c 'import json; print(json.load(open("update/manifest.json"))["versionName"])')"
version_code="$(python3 -c 'import json; print(json.load(open("update/manifest.json"))["versionCode"])')"
channel="$(python3 -c 'import json; print(json.load(open("update/manifest.json")).get("channel", "stable"))')"
derived="$RUNNER_TEMP/ios-release-derived"
payload="$RUNNER_TEMP/ios-release-package/Payload"
output="$RUNNER_TEMP/ios-release"
mkdir -p "$payload" "$output"
chmod +x gradlew

xcodebuild -project iosApp/iosApp.xcodeproj -scheme iosApp \
  -configuration Release -sdk iphoneos -destination 'generic/platform=iOS' \
  -derivedDataPath "$derived" CODE_SIGNING_ALLOWED=NO \
  MARKETING_VERSION="$version_name" CURRENT_PROJECT_VERSION="$version_code" \
  build

app="$derived/Build/Products/Release-iphoneos/yamibo_app.app"
test -d "$app"
if codesign -dv "$app" 2>/dev/null; then
  echo "Expected an unsigned app, but a code signature is present" >&2
  exit 1
fi
plutil -p "$app/Info.plist"
executable="$(/usr/libexec/PlistBuddy -c 'Print :CFBundleExecutable' "$app/Info.plist")"
file "$app/$executable"
xcrun vtool -show-build "$app/$executable"
ditto "$app" "$payload/yamibo_app.app"
ipa="$output/yamibo-${channel}-v${version_name}-unsigned.ipa"
ditto -c -k --keepParent "$payload" "$ipa"
unzip -t "$ipa"
shasum -a 256 "$ipa"
