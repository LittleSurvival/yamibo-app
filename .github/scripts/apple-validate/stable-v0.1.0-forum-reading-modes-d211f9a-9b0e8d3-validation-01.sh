#!/usr/bin/env bash
set -euo pipefail
mkdir -p "$APPLE_VALIDATE_OUTPUT"
xcodebuild -version > "$APPLE_VALIDATE_OUTPUT/xcode.txt"
xcrun simctl list devices available > "$APPLE_VALIDATE_OUTPUT/simulators.txt"
bash gradlew :shared:iosSimulatorArm64Test :composeApp:compileKotlinIosSimulatorArm64 --console=plain 2>&1 | tee "$APPLE_VALIDATE_OUTPUT/native-build.log"
python3 - "$APPLE_VALIDATE_OUTPUT" <<'PY'
import pathlib, shutil, sys, xml.etree.ElementTree as ET
out = pathlib.Path(sys.argv[1])
reports = list(pathlib.Path("shared/build/test-results/iosSimulatorArm64Test").glob("TEST-*.xml"))
tests = []
for path in reports:
    shutil.copy2(path, out / path.name)
    tests.extend(ET.parse(path).getroot().iter("testcase"))
names = {t.get("name", "") for t in tests}
assert any("allAuthorFloors" in name for name in names), names
assert len(tests) >= 7 and all(t.find("failure") is None and t.find("error") is None and t.find("skipped") is None for t in tests)
print(f"{len(tests)} shared reader tests on the iOS simulator target")
PY
