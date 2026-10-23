"""Branch-owned native and iPhone UI validation for downloaded content."""

import json
import os
from pathlib import Path
import plistlib
import shutil
import subprocess
import sys
import xml.etree.ElementTree as ET


PROFILE = Path(__file__).resolve().parent
OUTPUT = Path(os.environ["APPLE_VALIDATE_OUTPUT"])
DERIVED = (OUTPUT.parent / "derived-data").resolve()
BUNDLE = "com.yamibo.bbs.downloadqa"
PROJECT = "iosApp/iosApp.xcodeproj"
SCHEME = "DownloadedContentQA"


def run(label, command, timeout, *, env=None, capture=False):
    print(f"::group::{label}", flush=True)
    try:
        return subprocess.run(command, check=True, timeout=timeout, env=env,
                              text=True, stdout=subprocess.PIPE if capture else None)
    finally:
        print("::endgroup::", flush=True)


def preserve_native_results():
    source = Path("shared/build/test-results/iosSimulatorArm64Test")
    if not source.is_dir():
        return
    shutil.copytree(source, OUTPUT / "native-unit", dirs_exist_ok=True)


def main():
    simulator = None
    Path("gradlew").chmod(0o755)
    try:
        run("Native compilation and 40 regressions", ["./gradlew",
            ":composeApp:compileKotlinIosSimulatorArm64", ":composeApp:compileKotlinIosArm64",
            ":shared:iosSimulatorArm64Test", "--console=plain", "--max-workers=2"], 1200)
        preserve_native_results()
        cases = [case for path in (OUTPUT / "native-unit").glob("*.xml")
                 for case in ET.parse(path).getroot().iter("testcase")]
        native = {"tests": len(cases),
                  "failures": sum(case.find("failure") is not None for case in cases),
                  "errors": sum(case.find("error") is not None for case in cases),
                  "skipped": sum(case.find("skipped") is not None for case in cases)}
        (OUTPUT / "native-summary.json").write_text(json.dumps(native, indent=2), encoding="utf-8")
        assert native == {"tests": 40, "failures": 0, "errors": 0, "skipped": 0}, native

        run("Configure independent XCTest target", ["gem", "install", "xcodeproj",
            "-v", "1.27.0", "--no-document", "--user-install"], 180)
        run("Create QA scheme", ["ruby", str(PROFILE / "configure_tests.rb")], 60)
        devices = json.loads(subprocess.check_output(
            ["xcrun", "simctl", "list", "devices", "available", "-j"], text=True))
        runtime, phone = next((runtime, device)
            for runtime, devices_in_runtime in devices["devices"].items()
            for device in devices_in_runtime if "iPhone" in device["name"])
        simulator = subprocess.check_output(["xcrun", "simctl", "create",
            f"YamiboDownloadValidation-{os.getpid()}", phone["deviceTypeIdentifier"], runtime], text=True).strip()
        (OUTPUT / "simulator.json").write_text(json.dumps({"udid": simulator, "runtime": runtime,
            "deviceType": phone["deviceTypeIdentifier"]}, indent=2), encoding="utf-8")

        products = DERIVED / "Build/Products/Debug-iphonesimulator"
        products.mkdir(parents=True, exist_ok=True)
        sdk = subprocess.check_output(
            ["xcrun", "--sdk", "iphonesimulator", "--show-sdk-version"], text=True).strip()
        framework_env = dict(os.environ, SDK_NAME=f"iphonesimulator{sdk}", PLATFORM_NAME="iphonesimulator",
            CONFIGURATION="Debug", ARCHS="arm64", BUILT_PRODUCTS_DIR=str(products),
            TARGET_BUILD_DIR=str(products), FRAMEWORKS_FOLDER_PATH="yamibo_app.app/Frameworks",
            UNLOCALIZED_RESOURCES_FOLDER_PATH="yamibo_app.app", ENABLE_USER_SCRIPT_SANDBOXING="NO")
        run("Prepare framework before booting UI Simulator", ["./gradlew",
            ":composeApp:embedAndSignAppleFrameworkForXcode", "--console=plain", "--max-workers=2"],
            1800, env=framework_env)
        run("Stop Gradle before UI execution", ["./gradlew", "--stop"], 60)
        xcode = ["xcodebuild", "-project", PROJECT, "-scheme", SCHEME, "-configuration", "Debug",
                 "-destination", f"id={simulator}", "-derivedDataPath", str(DERIVED), "CODE_SIGNING_ALLOWED=NO"]
        run("Build App and UI test bundle", xcode + ["-sdk", "iphonesimulator", "build-for-testing"],
            600, env=dict(os.environ, OVERRIDE_KOTLIN_BUILD_IDE_SUPPORTED="YES"))

        apps = [path for path in products.glob("*.app")
                if plistlib.loads((path / "Info.plist").read_bytes()).get("CFBundleIdentifier") == BUNDLE]
        assert len(apps) == 1, apps
        run("Boot owned iPhone Simulator", ["xcrun", "simctl", "boot", simulator], 60)
        run("Wait for iPhone boot", ["xcrun", "simctl", "bootstatus", simulator, "-b"], 300)
        run("Install independent QA App", ["xcrun", "simctl", "install", simulator, str(apps[0])], 120)
        container = Path(subprocess.check_output(
            ["xcrun", "simctl", "get_app_container", simulator, BUNDLE, "data"], text=True).strip())
        fixture = container / "Documents/qa_downloads"
        run("Seed real storage manifests and images", [sys.executable,
            str(PROFILE / "generate_fixtures.py"), str(fixture)], 120)
        assert (fixture / "YamiboDownloads/thread_42_page_1_author_7/thread_page.json").is_file()
        print(f"Fixture root: {fixture}; manifests: {len(list(fixture.rglob('manifest.json')))}", flush=True)
        run("Run six actual reader and management UI cases", xcode + ["-resultBundlePath",
            str(OUTPUT / "ios-ui.xcresult"), "-parallel-testing-enabled", "NO", "test-without-building"], 720)
        summary = json.loads(run("Read individual UI execution summary", ["xcrun", "xcresulttool",
            "get", "test-results", "summary", "--path", str(OUTPUT / "ios-ui.xcresult")],
            60, capture=True).stdout)
        (OUTPUT / "ui-summary.json").write_text(json.dumps(summary, indent=2), encoding="utf-8")
        assert summary["passedTests"] == 6 and summary["failedTests"] == 0 and summary["skippedTests"] == 0, summary
    finally:
        preserve_native_results()
        if simulator:
            subprocess.run(["xcrun", "simctl", "shutdown", simulator], check=False, timeout=60)
            subprocess.run(["xcrun", "simctl", "delete", simulator], check=True, timeout=60)


if __name__ == "__main__":
    main()
