"""Export/restore an immutable, compiler-matched Kotlin framework between Apple QA jobs."""
from __future__ import annotations

import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import platform
import re
import shutil
import subprocess
import tarfile
import tempfile
import zipfile


def sha(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream,"sha256").hexdigest()


def conditions(sdk, config, networking):
    roots=[Path(s) for s in ["buildSrc/src","gradle/libs.versions.toml","gradle/wrapper/gradle-wrapper.properties",
                             "gradle.properties","build.gradle.kts","composeApp/build.gradle.kts","shared/build.gradle.kts","update/changelogs"]]
    for module in (Path("composeApp/src"),Path("shared/src")):
        roots.extend(p for p in module.iterdir() if p.name.endswith("Main") and "android" not in p.name.lower())
    h=hashlib.sha256()
    for root in sorted(roots):
        for path in sorted(root.rglob("*") if root.is_dir() else [root]):
            if path.is_file():
                h.update(path.as_posix().encode()+b"\0"+bytes.fromhex(sha(path)))
    build_settings=Path("settings.gradle.kts").read_text()
    if networking:
        build_settings=re.sub(r'includeBuild\("[^"]+reader-replay-yamibo-api"\)', 'includeBuild("QA_PINNED_SDK")',build_settings)
    h.update(build_settings.encode())
    return {"source_sha256":h.hexdigest(),"sdk":sdk,"target":"iosSimulatorArm64","architecture":platform.machine(),
            "configuration":config,"networking":networking,
            "upstream_sdk_commit":"6a794c8c3b7a4e14802d62d1d2c3b2c289685475",
            "xcode":subprocess.check_output(["xcodebuild","-version"],text=True).strip()}


def export(products,out,expected):
    primary=products/"ComposeApp.framework"
    if not primary.is_dir():
        primary=Path("composeApp/build/xcode-frameworks")/expected["configuration"]/f'iphonesimulator{expected["sdk"]}'/"ComposeApp.framework"
    assert (primary/"ComposeApp").is_file() and (primary/"Headers/ComposeApp.h").is_file(),primary
    archive=out/"reader-framework.tgz"
    with tarfile.open(archive,"w:gz",compresslevel=1,dereference=True) as tar:
        tar.add(primary,arcname="framework")
        # Preserve pre-Xcode resources staged by Gradle, without repeating its large .framework.
        for path in sorted(products.rglob("*")):
            rel=path.relative_to(products)
            if any(part.endswith(".framework") or part.endswith(".dSYM") for part in rel.parts):continue
            if path.is_file():tar.add(path,arcname="products/"+rel.as_posix(),recursive=False)
    aliases=[f.relative_to(products).as_posix() for f in products.rglob("ComposeApp.framework")]
    if "ComposeApp.framework" not in aliases:aliases.append("ComposeApp.framework")
    validation=subprocess.check_output(["git","-C",os.environ["GITHUB_WORKSPACE"],"rev-parse","HEAD"],text=True).strip()
    manifest={"conditions":expected,"validation_sha":validation,"payload_sha256":sha(archive),"aliases":aliases,
              "framework_binary_sha256":sha(primary/"ComposeApp")}
    (out/"reader-framework-manifest.json").write_text(json.dumps(manifest,indent=2))
    print("EXPORTED_FRAMEWORK "+json.dumps(manifest,separators=(",",":")),flush=True)


def restore(receipt,products,out,expected):
    got=re.fullmatch(r"(\d+):([a-f0-9]{40}):([a-f0-9]{64})",receipt)
    if not got:raise ValueError("Expected artifact-ID:validation-commit:official-archive-SHA256")
    aid,donor,digest=got.groups()
    artifact=f"repos/{os.environ['GITHUB_REPOSITORY']}/actions/artifacts/{aid}"
    metadata=json.loads(subprocess.check_output(["gh","api",artifact]))
    if metadata["digest"]!="sha256:"+digest or metadata["expired"]:
        raise ValueError("GitHub artifact metadata does not match the pinned digest")
    with tempfile.TemporaryDirectory(prefix="reader-framework-") as tmp:
        tmp=Path(tmp); outer=tmp/"hosted.zip"
        with outer.open("wb") as target:
            subprocess.run(["gh","api",artifact+"/zip"],stdout=target,check=True,timeout=300)
        if sha(outer)!=digest:raise ValueError("Transferred workflow archive digest mismatch")
        with zipfile.ZipFile(outer) as z:
            env=z.read("environment.log").decode()
            assert f"Commit: {donor}\n" in env
            manifest=json.loads(z.read("reader-framework-manifest.json"))
            assert manifest["validation_sha"]==donor and manifest["conditions"]==expected, {"donor":manifest["conditions"],"consumer":expected}
            payload=tmp/"payload.tgz"
            with z.open("reader-framework.tgz") as src,payload.open("wb") as dst:shutil.copyfileobj(src,dst)
        assert sha(payload)==manifest["payload_sha256"]
        stage=tmp/"stage";stage.mkdir()
        with tarfile.open(payload) as tar:
            for entry in tar.getmembers():
                parts=PurePosixPath(entry.name).parts
                if not parts or parts[0] not in ("framework","products") or ".." in parts or not(entry.isdir() or entry.isfile()):
                    raise ValueError("Unexpected member in framework archive")
            tar.extractall(stage,filter="data")
        assert sha(stage/"framework/ComposeApp")==manifest["framework_binary_sha256"]
        if (stage/"products").exists():shutil.copytree(stage/"products",products,dirs_exist_ok=True)
        primary=products/"ComposeApp.framework";shutil.copytree(stage/"framework",primary)
        for name in manifest["aliases"]:
            rel=PurePosixPath(name)
            if rel.name!="ComposeApp.framework" or rel.is_absolute() or ".." in rel.parts:raise ValueError("Invalid framework path")
            path=products/rel
            if path!=primary:path.parent.mkdir(parents=True,exist_ok=True);path.symlink_to(primary,target_is_directory=True)
        canonical=Path("composeApp/build/xcode-frameworks")/expected["configuration"]/f'iphonesimulator{expected["sdk"]}'/"ComposeApp.framework"
        canonical.parent.mkdir(parents=True,exist_ok=True)
        if not canonical.exists(): canonical.symlink_to(primary,target_is_directory=True)
        (out/"reused-framework.json").write_text(json.dumps({"receipt":receipt,"donor":manifest},indent=2))
        print("VERIFIED_FRAMEWORK "+manifest["framework_binary_sha256"],flush=True)
