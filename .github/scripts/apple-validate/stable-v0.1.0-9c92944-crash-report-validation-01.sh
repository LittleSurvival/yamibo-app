#!/usr/bin/env bash
set -euo pipefail
cd "$(git rev-parse --show-toplevel)"
out="${APPLE_VALIDATE_OUTPUT:?}"
support=.github/scripts/apple-validate/crash-report
mkdir -p "$out"
derived="$(mktemp -d)"
udid=
cleanup() {
  if [[ -n "$udid" ]]; then
    xcrun simctl shutdown "$udid" || true
    xcrun simctl delete "$udid"
  fi
  rm -rf "$derived"
}
trap cleanup EXIT
echo "::group::Prepare isolated UI target and fatal Kotlin QA entry"
gem install xcodeproj -v 1.27.0 --no-document --user-install
ruby "$support/configure_tests.rb"
python3 "$support/instrument.py"
echo "::endgroup::"
echo "::group::Prepare arm64 simulator framework"
products="$derived/Build/Products/Debug-iphonesimulator"
mkdir -p "$products"
SDK_NAME="iphonesimulator$(xcrun --sdk iphonesimulator --show-sdk-version)" \
  PLATFORM_NAME=iphonesimulator CONFIGURATION=Debug ARCHS=arm64 \
  BUILT_PRODUCTS_DIR="$products" TARGET_BUILD_DIR="$products" \
  FRAMEWORKS_FOLDER_PATH=yamibo_app.app/Frameworks UNLOCALIZED_RESOURCES_FOLDER_PATH=yamibo_app.app \
  ENABLE_USER_SCRIPT_SANDBOXING=NO \
  bash gradlew :composeApp:embedAndSignAppleFrameworkForXcode --console=plain --max-workers=2
bash gradlew --stop
echo "::endgroup::"
echo "::group::Build test bundle and boot owned iPhone"
xcrun simctl list devices available -j > "$out/devices.json"
read -r runtime model < <(python3 - "$out/devices.json" <<'PY'
import json, sys
for runtime, devices in reversed(list(json.load(open(sys.argv[1]))["devices"].items())):
    for device in devices:
        if "iPhone" in device["name"] and ".iOS-" in runtime:
            print(runtime, device["deviceTypeIdentifier"])
            sys.exit(0)
raise SystemExit("No available iPhone")
PY
)
udid="$(xcrun simctl create "YamiboCrashQA-$$" "$model" "$runtime")"
echo "Simulator $udid $runtime $model"
export OVERRIDE_KOTLIN_BUILD_IDE_SUPPORTED=YES
build=(xcodebuild -project iosApp/iosApp.xcodeproj -scheme CrashReportQA -configuration Debug
       -destination "id=$udid" -derivedDataPath "$derived" CODE_SIGNING_ALLOWED=NO)
"${build[@]}" -sdk iphonesimulator build-for-testing
xcrun simctl boot "$udid"
xcrun simctl bootstatus "$udid" -b
echo "::endgroup::"
bundle=com.yamibo.bbs
for theme in DEFAULT CLASSIC_BLACK; do
  echo "::group::Standalone fatal crash and recovery ($theme)"
  mkdir -p "$out/$theme"
  xcrun simctl install "$udid" "$products/yamibo_app.app"
  container="$(xcrun simctl get_app_container "$udid" "$bundle" data)"
  crash="CRASH_QA_${theme}_$(uuidgen)"
  marker="$container/Library/Application Support/CrashReports/kotlin.json"
  [[ ! -e "$marker" ]]
  SIMCTL_CHILD_QA_CRASH_TRIGGER="$crash" xcrun simctl launch "$udid" "$bundle" | tee "$out/$theme/launch.txt"
  python3 "$support/verify.py" crash "$out/$theme" "$container" "$crash"
  if [[ "$theme" = DEFAULT ]]; then test_case=testDefaultRecovery; else test_case=testClassicBlackRecovery; fi
  "${build[@]}" -parallel-testing-enabled NO -only-testing:"CrashReportUITests/CrashReportUITests/$test_case" \
    -resultBundlePath "$out/$theme.xcresult" test-without-building
  container="$(xcrun simctl get_app_container "$udid" "$bundle" data)"
  runner="$(xcrun simctl get_app_container "$udid" com.yamibo.bbs.crashqa.uitests.xctrunner data)"
  xcrun simctl pbpaste "$udid" > "$out/$theme/clipboard.txt"
  python3 "$support/verify.py" recovery "$out/$theme" "$container" "$runner"
  cp "$runner/tmp/recovery-$theme.png" "$out/$theme/recovery.png"
  swift "$support/check_theme.swift" "$out/$theme/recovery.png" "$theme"
  xcrun simctl uninstall "$udid" "$bundle"
  echo "::endgroup::"
done
