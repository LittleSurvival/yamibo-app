#!/usr/bin/env bash
set -euo pipefail
out="${APPLE_VALIDATE_OUTPUT:?}"
dir=.github/scripts/apple-validate/recovery-ui
derived="$RUNNER_TEMP/recovery-build"
ruby -e 'require "xcodeproj"' || gem install --user-install xcodeproj --no-document
ruby "$dir/configure_tests.rb"
python3 - > "$RUNNER_TEMP/recovery-device.txt" <<'PY'
import json, subprocess
def entries(key): return json.loads(subprocess.check_output(['xcrun', 'simctl', 'list', key, '-j']))[key]
r = max((r for r in entries('runtimes') if r.get('isAvailable') and '.iOS-' in r['identifier']), key=lambda r: tuple(map(int, r['version'].split('.'))))
available = {d['identifier'] for d in r.get('supportedDeviceTypes', [])}
d = next(d for d in reversed(entries('devicetypes')) if d['name'].startswith('iPhone') and (not available or d['identifier'] in available))
print(r['identifier'], d['identifier'])
PY
read -r runtime device_type < "$RUNNER_TEMP/recovery-device.txt"
udid="$(xcrun simctl create "Recovery-$GITHUB_RUN_ID" "$device_type" "$runtime")"
trap 'xcrun simctl shutdown "$udid" || true; xcrun simctl delete "$udid"' EXIT
echo "Testing $udid ($device_type, $runtime)"
(
  export SDK_NAME="iphonesimulator$(xcrun --sdk iphonesimulator --show-sdk-version)"
  export PLATFORM_NAME=iphonesimulator CONFIGURATION=Debug ARCHS=arm64
  export BUILT_PRODUCTS_DIR="$derived/Build/Products/Debug-iphonesimulator"
  export TARGET_BUILD_DIR="$BUILT_PRODUCTS_DIR" FRAMEWORKS_FOLDER_PATH=yamibo_app.app/Frameworks
  export UNLOCALIZED_RESOURCES_FOLDER_PATH=yamibo_app.app ENABLE_USER_SCRIPT_SANDBOXING=NO
  mkdir -p "$BUILT_PRODUCTS_DIR"
  bash gradlew :composeApp:embedAndSignAppleFrameworkForXcode --console=plain --max-workers=2
  bash gradlew --stop
)
xcrun simctl boot "$udid"
xcrun simctl bootstatus "$udid" -b
args=(-project iosApp/iosApp.xcodeproj -scheme RecoveryQA -configuration Debug -derivedDataPath "$derived"
  -destination "platform=iOS Simulator,id=$udid" -parallel-testing-enabled NO CODE_SIGNING_ALLOWED=NO
  YAMIBO_IOS_BUNDLE_ID=com.yamibo.bbs.recoveryqa OVERRIDE_KOTLIN_BUILD_IDE_SUPPORTED=YES)
xcodebuild "${args[@]}" -sdk iphonesimulator build-for-testing
xcodebuild "${args[@]}" -resultBundlePath "$out/Recovery.xcresult" test-without-building
