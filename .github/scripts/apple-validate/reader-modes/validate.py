"""Run the three downloaded-reader UI paths on a dedicated iPhone Simulator."""

import hashlib
import zipfile
import json
import os
from pathlib import Path
import plistlib
import subprocess
import sys


PROFILE = Path(__file__).resolve().parent
OUTPUT = Path(os.environ["APPLE_VALIDATE_OUTPUT"])
DERIVED = (OUTPUT.parent / "derived-data").resolve()
BUNDLE = "com.yamibo.bbs.readermodesqa"
PROJECT = "iosApp/iosApp.xcodeproj"
SCHEME = "ReaderModesQA"


def run(label, command, timeout, *, env=None, capture=False):
    print(f"::group::{label}", flush=True)
    try:
        return subprocess.run(command, check=True, timeout=timeout, env=env,
                              text=True, stdout=subprocess.PIPE if capture else None)
    finally:
        print("::endgroup::", flush=True)


def main():
    simulator = None
    Path("gradlew").chmod(0o755)
    try:
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
        simctl = ["xcrun", "simctl"]
        def downloads_root():
            container = subprocess.check_output(simctl + ["get_app_container", simulator, BUNDLE, "data"], text=True).strip()
            return Path(container) / "Documents/YamiboApp"

        results = {}
        for palette in ("default", "default-dark"):
            for reader in ("testForum", "testNovel", "testComic"):
                case = reader if palette == "default" else f"{reader}Dark"
                name = f"{palette}-{reader}"
                run(f"Install clean App for {name}", simctl + ["install", simulator, str(apps[0])], 120)
                fixture = downloads_root()
                damaged = case == "testForumDark"
                run("Seed downloaded page", [sys.executable, str(PROFILE / "generate_fixtures.py"), str(fixture)] + (["--cbz"] if palette == "default-dark" else []) + (["--damaged"] if damaged else []), 120)
                bundle = OUTPUT / f"{name}.xcresult"
                try:
                    run(name, xcode + ["-resultBundlePath", str(bundle), "-parallel-testing-enabled", "NO",
                        f"-only-testing:ReaderModesUITests/ReaderModesUITests/{case}", "test-without-building"], 300)
                    summary = json.loads(subprocess.check_output(["xcrun", "xcresulttool", "get",
                        "test-results", "summary", "--path", str(bundle)], text=True))
                    # XCTest may reinstall the app, moving its data to a new container UUID.
                    fixture = downloads_root()
                    library = fixture / "YamiboDownloads" / "library"
                    manifests = list(library.glob("*/threads/140/manifest.json"))
                    assert len(manifests) == 1, (fixture, list(fixture.rglob("manifest.json")))
                    state = json.loads(manifests[0].read_text(encoding="utf-8"))
                    claim = state["claims"][0]
                    assert [f["pid"] for f in claim["floors"]] == [1401, 1402, 1403]
                    pack = manifests[0].parent / ("posts-" + state["generation"] + ".zip")
                    with zipfile.ZipFile(pack) as archive:
                        assert sorted(archive.namelist()) == ["1401.json", "1402.json", "1403.json"]
                    blobs = list(library.glob("*/images/*/*"))
                    assert len(blobs) == (2 if damaged else 3)
                    assert claim["item"].get("missingImageCount", 0) == (1 if damaged else 0)
                    assert all(hashlib.sha256(f.read_bytes()).hexdigest() == f.name for f in blobs)
                    assert not (fixture / "YamiboDownloads/thread_140_page_1_author_7").exists()
                    results[name] = (summary["passedTests"] == 1 and summary["failedTests"] == 0
                                     and summary["skippedTests"] == 0)
                except (subprocess.CalledProcessError, subprocess.TimeoutExpired) as error:
                    print(f"{name}: {error}", flush=True)
                    results[name] = False
                run("Remove QA App and its reading history", simctl + ["uninstall", simulator, BUNDLE], 60)
                (OUTPUT / "ui-summary.json").write_text(json.dumps(results, indent=2), encoding="utf-8")
        if not all(results.values()):
            raise RuntimeError(f"Reader UI results: {results}")
    finally:
        if simulator:
            subprocess.run(["xcrun", "simctl", "shutdown", simulator], check=False, timeout=60)
            subprocess.run(["xcrun", "simctl", "delete", simulator], check=True, timeout=60)


if __name__ == "__main__":
    main()
