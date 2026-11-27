"""Run the three downloaded-reader UI paths on a dedicated iPhone Simulator."""

import hashlib
import zipfile
import json
import os
from pathlib import Path
import plistlib
import subprocess
import sys
import tempfile
import shutil
import signal
import time
import threading
from contextlib import ExitStack
from repair_origin import RepairOrigin


PROFILE = Path(__file__).resolve().parent
OUTPUT = Path(os.environ["APPLE_VALIDATE_OUTPUT"])
DERIVED = Path(os.environ.get("READER_PAIR_DERIVED",OUTPUT.parent / "derived-data")).resolve()
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
    booted = False
    shared_simulator = os.environ.get("READER_PAIRED_SIMULATOR")
    resources = ExitStack()
    perf = "--perf" in sys.argv
    scale = tuple(map(int,sys.argv[sys.argv.index("--scale")+1:sys.argv.index("--scale")+3])) if "--scale" in sys.argv else None
    network_mode = sys.argv[sys.argv.index("--network")+1] if "--network" in sys.argv else None
    sustain = "--sustain" in sys.argv
    guard_local = "--guard-local" in sys.argv
    long_image = "--long-image" in sys.argv
    config = "Debug"
    if perf:
        os.environ["READER_PERF"] = "1"
        mode, sequence, rounds_raw, config = sys.argv[sys.argv.index("--perf")+1:]
        assert mode in ("Comic","Novel","Forum") and sequence in ("cold","warm")
        assert config in ("Debug", "Release")
        override=os.environ.get("READER_ROUND_COUNT","")
        if shared_simulator and override:
            raise ValueError("Paired runs define their individual input count")
        rounds = int(override or rounds_raw)
        assert 1 <= rounds <= 40
        (OUTPUT/"profile-conditions.json").write_text(json.dumps({"mode":mode,"sequence":sequence,"demanded_samples":rounds,"profile_default_samples":int(rounds_raw),"configuration":config,
            "network":network_mode,"guard_local":guard_local,"native_long":long_image,"catalog_scale":scale,"sustain":sustain,
            "prompt_input":"--prompt-input" in sys.argv,"reference":"12" if "--tag-12" in sys.argv else "candidate"}))
        testsrc = PROFILE / "ReaderModesUITests.swift"
        program = testsrc.read_text().replace("1 // READER_PERF_ROUNDS", f"{rounds} // READER_PERF_ROUNDS")
        if guard_local:
            program=program.replace("false // READER_PERF_GUARD","true // READER_PERF_GUARD")
        if "--prompt-input" in sys.argv:
            program=program.replace("false // READER_PERF_PROMPT","true // READER_PERF_PROMPT")
        if network_mode:
            assert network_mode in ("fast","slow")
            program=program.replace('"none" // READER_PERF_NETWORK',f'"{network_mode}" // READER_PERF_NETWORK')
        if scale:
            program=program.replace("0 // READER_PERF_CHAPTERS",f"{scale[0]} // READER_PERF_CHAPTERS")
        testsrc.write_text(program.replace("true // READER_PERF_COLD", f"{str(sequence=='cold').lower()} // READER_PERF_COLD"))

    Path("gradlew").chmod(0o755)
    try:
        run("Configure independent XCTest target", ["gem", "install", "xcodeproj",
            "-v", "1.27.0", "--no-document", "--user-install"], 180)
        if network_mode or guard_local:
            import urllib.request
            sdk=Path(os.environ["RUNNER_TEMP"])/"reader-replay-yamibo-api"
            upstream="6a794c8c3b7a4e14802d62d1d2c3b2c289685475"
            run("Checkout the tagged SDK sources",["git","clone","--depth=1","--branch","v1.1.29","https://github.com/LittleSurvival/yamibo-api.git",str(sdk)],180)
            assert subprocess.check_output(["git","-C",str(sdk),"rev-parse","HEAD"],text=True).strip()==upstream
            route=sdk/"library/src/commonMain/kotlin/io/github/littlesurvival/YamiboRoute.kt"
            source=route.read_text();old='internal val domain = "https://bbs.yamibo.com/"'
            assert source.count(old)==1
            route.write_text(source.replace(old,'internal val domain = "http://127.0.0.1:18928/"'))
            (OUTPUT/"sdk-origin.patch").write_bytes(subprocess.check_output(["git","-C",str(sdk),"diff"]))
            settings=Path("settings.gradle.kts")
            with settings.open("a") as out:
                out.write(f'\nincludeBuild("{sdk}") {{ dependencySubstitution {{ substitute(module("io.github.littlesurvival:yamibo-api")).using(project(":library")) }} }}\n')
            info=Path("iosApp/iosApp/Info.plist"); plist=plistlib.loads(info.read_bytes())
            plist["NSAppTransportSecurity"]={"NSAllowsLocalNetworking":True,"NSExceptionDomains":{"127.0.0.1":{"NSExceptionAllowsInsecureHTTPLoads":True}}}
            info.write_bytes(plistlib.dumps(plist))
            if mode=="Novel" and network_mode:
                # FID55 uses the native novel-details entry in *both* versions; FID30 is a normal forum on tag12.
                origin_script=PROFILE/"replay_origin.py"
                text=origin_script.read_text()
                assert text.count("fid=30") >= 3
                origin_script.write_text(text.replace("fid=30","fid=55"))
                testsrc.write_text(testsrc.read_text().replace("-appsettings.forumReadingMode.30","-appsettings.forumReadingMode.55"))
            run("Generate fixed shaped HTTP corpus",[sys.executable,str(PROFILE/"replay_origin.py"),"--generate",str(PROFILE/"generate_fixtures.py")],120)
            origin_process=subprocess.Popen([sys.executable,str(PROFILE/"replay_origin.py")])
            resources.callback(lambda: (origin_process.terminate(),origin_process.wait(timeout=10)))
            for _ in range(50):
                try:
                    urllib.request.urlopen(f"http://127.0.0.1:18928/__case/ios-{mode.lower()}-{network_mode}",timeout=2).read()
                    break
                except OSError: time.sleep(.2)
            else: raise RuntimeError("Loopback origin failed to start")
            def collect_origin():
                for name in ("contract.json","begins.ndjson","requests.ndjson","generator.log"):
                    f=PROFILE/"network-fixture"/name
                    if f.is_file():shutil.copy2(f,OUTPUT)
            resources.callback(collect_origin)
        run("Create QA scheme", ["ruby", str(PROFILE / "configure_tests.rb")], 60)
        devices = json.loads(subprocess.check_output(
            ["xcrun", "simctl", "list", "devices", "available", "-j"], text=True))
        runtime, phone = next((runtime, device)
            for runtime, devices_in_runtime in devices["devices"].items()
            for device in devices_in_runtime if (runtime.endswith(".iOS-26-4") and device["name"]=="iPhone 17 Pro" if perf else "iPhone" in device["name"]))
        if shared_simulator:
            actual=next(d for d in devices["devices"][runtime] if d["udid"]==shared_simulator)
            assert actual["state"]=="Booted" and actual["deviceTypeIdentifier"]==phone["deviceTypeIdentifier"]
        simulator = shared_simulator or subprocess.check_output(["xcrun", "simctl", "create",
            f"YamiboDownloadValidation-{os.getpid()}", phone["deviceTypeIdentifier"], runtime], text=True).strip()
        (OUTPUT / "simulator.json").write_text(json.dumps({"udid": simulator, "runtime": runtime,
            "deviceType": phone["deviceTypeIdentifier"]}, indent=2), encoding="utf-8")

        products = DERIVED / f"Build/Products/{config}-iphonesimulator"
        products.mkdir(parents=True, exist_ok=True)
        sdk = subprocess.check_output(
            ["xcrun", "--sdk", "iphonesimulator", "--show-sdk-version"], text=True).strip()
        framework_env = dict(os.environ, SDK_NAME=f"iphonesimulator{sdk}", PLATFORM_NAME="iphonesimulator",
            CONFIGURATION=config, ARCHS="arm64", BUILT_PRODUCTS_DIR=str(products),
            TARGET_BUILD_DIR=str(products), FRAMEWORKS_FOLDER_PATH="yamibo_app.app/Frameworks",
            UNLOCALIZED_RESOURCES_FOLDER_PATH="yamibo_app.app", ENABLE_USER_SCRIPT_SANDBOXING="NO")
        compiler_args=["--max-workers=1","-Dorg.gradle.jvmargs=-Xmx6g -Dfile.encoding=UTF-8"] if config=="Release" else ["--max-workers=2"]
        if perf:
            (OUTPUT/"runner-hardware.txt").write_text(subprocess.check_output(["sysctl","hw.model","hw.ncpu","hw.memsize","machdep.cpu.brand_string"],text=True)+"\n"+" ".join(compiler_args))
        receipt=os.environ.get("READER_FRAMEWORK_RECEIPT","")
        if perf and (receipt or "--export-framework" in sys.argv):
            from perf_framework import conditions as framework_conditions,export,restore
            definition=framework_conditions(sdk,config,bool(network_mode or guard_local))
        if receipt:
            assert perf and config=="Release"
            restore(receipt,products,OUTPUT,definition)
        else:
            run("Prepare framework before booting UI Simulator", ["./gradlew",
                ":composeApp:embedAndSignAppleFrameworkForXcode", "--console=plain", *compiler_args],
                4500 if config=="Release" else 1800, env=framework_env)
            run("Stop Gradle before UI execution", ["./gradlew", "--stop"], 60)
        if "--export-framework" in sys.argv:
            assert not receipt
            export(products,OUTPUT,definition)
            return
        xcode = ["xcodebuild", "-project", PROJECT, "-scheme", SCHEME, "-configuration", config,
                 "-destination", f"id={simulator}", "-derivedDataPath", str(DERIVED), "CODE_SIGNING_ALLOWED=NO"]
        run("Build App and UI test bundle", xcode + ["-sdk", "iphonesimulator", "build-for-testing"],
            600, env=dict(os.environ, OVERRIDE_KOTLIN_BUILD_IDE_SUPPORTED="YES"))

        apps = [path for path in products.glob("*.app")
                if plistlib.loads((path / "Info.plist").read_bytes()).get("CFBundleIdentifier") == BUNDLE]
        assert len(apps) == 1, apps
        if not shared_simulator:
            run("Boot owned iPhone Simulator", ["xcrun", "simctl", "boot", simulator], 60)
        booted=True
        run("Wait for iPhone boot", ["xcrun", "simctl", "bootstatus", simulator, "-b"], 300)
        simctl = ["xcrun", "simctl"]
        def downloads_root():
            container = subprocess.check_output(simctl + ["get_app_container", simulator, BUNDLE, "data"], text=True).strip()
            return Path(container) / "Documents/YamiboApp"

        results = {}
        matrix = "--matrix" in sys.argv[1:]
        palettes = ("default",) if matrix else ("default", "default-dark")
        readers = (("testComicToNovel", "testNovelToComic", "testComicToForum", "testForumToComic",
                    "testNovelToForum", "testForumToNovel") if matrix else ("testForum", "testNovel", "testComic"))
        origin = None
        if "--repair" in sys.argv:
            origin = RepairOrigin(simulator, resources.enter_context(tempfile.TemporaryDirectory()))
            resources.callback(origin.close)
            readers = ("testRepair",)
        if perf:
            readers, palettes = (("testSustainedOpenAndReturn" if sustain else ("testNativeLongFirstImage" if long_image else f"testPerf{mode}")),), ("default",)
        for palette in palettes:
            for reader in readers:
                case = reader if palette == "default" else f"{reader}Dark"
                name = f"{palette}-{reader}"
                run(f"Install clean App for {name}", simctl + ["install", simulator, str(apps[0])], 120)
                fixture = downloads_root()
                damaged = origin is not None or case in ("testForumDark", "testComicDark")
                fixture_args = ["--cbz"] if origin or palette == "default-dark" else []
                if perf and config == "Release":
                    fixture_args.append("--bench")
                    if guard_local:fixture_args.extend(["--origin","http://127.0.0.1:18928"])
                    if sustain: fixture_args.append("--pure-text")
                if damaged:
                    fixture_args.append("--damaged")
                if origin:
                    fixture_args.extend(["--origin", origin.begin(case), "--repair-image", str(origin.image)])
                if network_mode:
                    pass  # This scenario has no downloaded thread.
                elif long_image:
                    # Produced by this checkout's native repository; preserve the actual storage bytes.
                    with zipfile.ZipFile(OUTPUT/"long-thread-native-fixture.zip") as archive:
                        assert all(name.startswith("YamiboDownloads/") and ".." not in Path(name).parts for name in archive.namelist())
                        archive.extractall(fixture)
                    blobs = list(fixture.glob("YamiboDownloads/library/0/images/*/*"))
                    assert len(blobs)==1 and hashlib.sha256(blobs[0].read_bytes()).hexdigest()=="0e92708f9b5063336c4036998ab625845bcf983de1cc212d5bb3851df8d0fbc4"
                    shutil.copy2(blobs[0],OUTPUT/"native-image.png")
                else:
                    run("Seed downloaded page", [sys.executable, str(PROFILE / "generate_fixtures.py"), str(fixture)] + fixture_args, 120)
                if scale:
                    from seed_perf_scale import seed
                    # Bootstrap the application's own database schema, stop it, then seed the isolated simulator.
                    run("Initialize native SQLite in the QA app",simctl+["launch",simulator,BUNDLE,"-uid","7","-username","QA Bench","-userGroup","Fixture"],60)
                    deadline=time.monotonic()+60; database=None
                    while time.monotonic()<deadline:
                        databases=list(fixture.parent.parent.rglob("yamibo.db"))
                        if databases:
                            import sqlite3
                            with sqlite3.connect(databases[0]) as conn:
                                if conn.execute("SELECT 1 FROM sqlite_master WHERE name='SmartComicMember'").fetchone():
                                    database=databases[0];break
                        time.sleep(.2)
                    assert database, "Native database schema not available"
                    run("Stop preparation process",simctl+["terminate",simulator,BUNDLE],30)
                    seed(fixture,database,*scale,OUTPUT)
                bundle = OUTPUT / f"{name}.xcresult"
                try:
                    movie = OUTPUT / "presented.mp4"
                    if perf:
                        recording = subprocess.Popen(simctl + ["io", simulator, "recordVideo", "--codec=h264", str(movie)])
                        time.sleep(1)
                    if sustain:
                        done=threading.Event()
                        executable=plistlib.loads((apps[0]/"Info.plist").read_bytes())["CFBundleExecutable"]
                        def sample_rss():
                            with (OUTPUT/"reader-process.ndjson").open("w",buffering=1) as samples:
                                while not done.is_set():
                                    at=time.monotonic_ns()
                                    for line in subprocess.check_output(["ps","-axo","pid=,rss=,comm="],text=True).splitlines():
                                        parts=line.strip().split(maxsplit=2)
                                        if len(parts)==3 and parts[2].endswith(".app/"+executable) and simulator in parts[2]:
                                            samples.write(json.dumps({"host_ns":at,"wall":time.time(),"pid":int(parts[0]),"rss_bytes":int(parts[1])*1024})+"\n")
                                    done.wait(.5)
                        observer=threading.Thread(target=sample_rss,daemon=True);observer.start()
                    fs_watch=None
                    if scale:
                        fs_log=resources.enter_context((OUTPUT/"fs-usage.log").open("w"))
                        binary=plistlib.loads((apps[0]/"Info.plist").read_bytes())["CFBundleExecutable"]
                        (OUTPUT/"file-observer.json").write_text(json.dumps({"executable":binary,"tool":"fs_usage -w -f filesys","utc_offset_seconds":time.localtime().tm_gmtoff}))
                        fs_watch=subprocess.Popen(["sudo","-n","fs_usage","-w","-f","filesys",binary],stdout=fs_log,stderr=subprocess.STDOUT)
                    test_problem=None
                    try:
                        run(name, xcode + ["-resultBundlePath", str(bundle), "-parallel-testing-enabled", "NO",
                            f"-only-testing:ReaderModesUITests/ReaderModesUITests/{case}", "test-without-building"], max(1800,rounds*100) if perf else 300)
                    except (subprocess.CalledProcessError,subprocess.TimeoutExpired) as exc:
                        test_problem=exc
                    finally:
                        if sustain: done.set();observer.join(timeout=6)
                        if fs_watch and fs_watch.poll() is None:
                            fs_watch.send_signal(signal.SIGINT)
                            try: fs_watch.wait(timeout=5)
                            except subprocess.TimeoutExpired: fs_watch.terminate()
                        if perf:
                            recording.send_signal(signal.SIGINT)
                            recording.wait(timeout=45)
                            docs = downloads_root().parent
                            for path in docs.glob("reader-perf-events-*.jsonl"):
                                shutil.copy2(path, OUTPUT)
                            attachments = OUTPUT / "perf-attachments"
                            subprocess.run(["xcrun","xcresulttool","export","attachments","--path",str(bundle),"--output-path",str(attachments)], check=False)
                    if perf:
                        if network_mode or guard_local: collect_origin()
                        run("Inspect encoded content and input traces",[sys.executable,str(PROFILE/"read_perf_frames.py"),str(OUTPUT),("Sustain" if sustain else ("NativeLong" if long_image else mode)),sequence,str(rounds),config],1200)
                        if test_problem: raise test_problem
                        if scale:
                            import sqlite3
                            database=next(downloads_root().parent.parent.rglob("yamibo.db"))
                            with sqlite3.connect(f"file:{database}?mode=ro",uri=True) as db:
                                progress=db.execute("SELECT h.threadId,h.pageIndex,h.totalPages,a.floorPid FROM ImageReadingHistory h LEFT JOIN ReaderImageAnchor a ON a.historyPostId=h.postId AND a.observedAt=h.lastVisitTime WHERE h.threadId IN (140,300004) ORDER BY h.threadId").fetchall()
                            (OUTPUT/"catalog-progress.json").write_text(json.dumps(progress))
                            if not any(row[0]==300004 and row[2]==1 and row[3]==3000041 for row in progress):
                                raise AssertionError(f"Next-chapter progress was not persisted: {progress}")
                        results[name] = True
                        continue
                    if test_problem: raise test_problem
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
                    missing = int(damaged and origin is None)
                    assert len(blobs) == 3 - missing
                    assert claim["item"].get("missingImageCount", 0) == missing
                    if origin:
                        assert origin.requests == [{"path": f"/{case}/marker-3.png", "armed": True}], origin.requests
                        assert hashlib.sha256(origin.image.read_bytes()).hexdigest() in [f.name for f in blobs]
                    assert all(hashlib.sha256(f.read_bytes()).hexdigest() == f.name for f in blobs)
                    assert not (fixture / "YamiboDownloads/thread_140_page_1_author_7").exists()
                    results[name] = (summary["passedTests"] == 1 and summary["failedTests"] == 0
                                     and summary["skippedTests"] == 0)
                except (AssertionError, subprocess.CalledProcessError, subprocess.TimeoutExpired) as error:
                    print(f"{name}: {error}", flush=True)
                    results[name] = False
                if origin:
                    (OUTPUT / f"{name}-requests.json").write_text(json.dumps(origin.requests, indent=2))
                run("Remove QA App and its reading history", simctl + ["uninstall", simulator, BUNDLE], 60)
                (OUTPUT / "ui-summary.json").write_text(json.dumps(results, indent=2), encoding="utf-8")
        if not all(results.values()):
            raise RuntimeError(f"Reader UI results: {results}")
    finally:
        resources.close()
        if simulator and not shared_simulator:
            if booted: subprocess.run(["xcrun", "simctl", "shutdown", simulator], check=False, timeout=60)
            subprocess.run(["xcrun", "simctl", "delete", simulator], check=True, timeout=60)


if __name__ == "__main__":
    main()
