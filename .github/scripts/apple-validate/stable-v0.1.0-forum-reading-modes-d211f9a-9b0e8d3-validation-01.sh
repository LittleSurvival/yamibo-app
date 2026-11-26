#!/usr/bin/env bash
set -euo pipefail
mkdir -p "$APPLE_VALIDATE_OUTPUT"
xcodebuild -version > "$APPLE_VALIDATE_OUTPUT/xcode.txt"
xcrun simctl list devices available > "$APPLE_VALIDATE_OUTPUT/simulators.txt"
trap 'for report in shared/build/test-results/iosSimulatorArm64Test/TEST-*.xml; do [[ ! -f "$report" ]] || cp "$report" "$APPLE_VALIDATE_OUTPUT/"; done' EXIT
bash gradlew :shared:iosSimulatorArm64Test :composeApp:compileKotlinIosSimulatorArm64 --console=plain 2>&1 | tee "$APPLE_VALIDATE_OUTPUT/native-build.log"
python3 - "$APPLE_VALIDATE_OUTPUT" <<'PY'
import json, pathlib, shutil, sys, xml.etree.ElementTree as ET
out = pathlib.Path(sys.argv[1])
reports = list(pathlib.Path("shared/build/test-results/iosSimulatorArm64Test").glob("TEST-*.xml"))
tests = []
for path in reports:
    shutil.copy2(path, out / path.name)
    tests.extend(ET.parse(path).getroot().iter("testcase"))
expected = {
    "allAuthorFloorsKeepTheirOwnPidAndRepeatedImageOccurrence",
    "hiddenLoadingImageDoesNotPrecedeTheFirstVisibleImage",
    "prefersTheSameLazyImageSourceAsTheTextReader",
    "excludesPlaceholdersAndEmoticonsWithoutDroppingRepeatedContent",
    "preservesInlineAndLocalUrisAndClickedImageIndex",
    "emptyVisibleContentDoesNotReintroduceHiddenImagesFromApiList",
    "missingHtmlFallsBackToFilteredNormalizedApiSources",
    "copiesAreIsolatedByAccountStorageAndPagination",
    "deletingReadingHistoryClearsTheWorkResumeButRetainsMembershipAndDownload",
    "currentBackupRestoresTextAndImageAnchorsIntoFreshNativeDatabase",
    "olderWritesAndSameTimeLegacyImageReplayKeepTheCorrectPosition",
}
names = [case.attrib["name"].split("[")[0] for case in tests]
failed = [name for name, case in zip(names, tests)
          if any(case.find(tag) is not None for tag in ("failure", "error", "skipped"))]
summary = {"executed": names, "missing": sorted(expected - set(names)), "failedOrSkipped": failed}
(out / "native-summary.json").write_text(json.dumps(summary, indent=2), encoding="utf-8")
if summary["missing"] or failed or len(names) != len(set(names)):
    raise SystemExit(f"Incomplete iOS reader coverage: {summary}")
print(f"{len(tests)} shared reader tests on the iOS simulator target")
PY
