#!/usr/bin/env bash
set -euo pipefail
chmod +x gradlew
./gradlew :composeApp:compileKotlinIosArm64 :composeApp:compileKotlinIosSimulatorArm64 :shared:iosSimulatorArm64Test --console=plain
cp -R shared/build/test-results/iosSimulatorArm64Test "$APPLE_VALIDATE_OUTPUT/test-results"
python3 - "$APPLE_VALIDATE_OUTPUT/test-results" <<'PY'
from pathlib import Path
import sys, xml.etree.ElementTree as ET
cases = [case for path in Path(sys.argv[1]).rglob("*.xml") for case in ET.parse(path).getroot().iter("testcase") if "DownloadArchive" in case.get("classname", "")]
assert len(cases) >= 3, cases
for case in cases:
    print(case.get("name"), case.get("time"))
    assert all(case.find(tag) is None for tag in ("failure", "error", "skipped"))
PY
