#!/usr/bin/env python3
"""Native packaging metadata and GitHub-only publication; no manually maintained desktop manifest."""
import argparse
import hashlib
import json
from pathlib import Path
import platform
import re
import shutil
import subprocess
import sys
import tempfile

REPO = "LittleSurvival/yamibo-app"
FEED = "desktop-update-release"
EXPECTED = {("windows", "x86_64", "msi"), ("macos", "x86_64", "dmg"),
            ("macos", "arm64", "dmg"), ("linux", "x86_64", "deb"), ("linux", "x86_64", "rpm")}
OUTPUT = Path("build/desktop-release")
PACKAGES = Path("composeApp/build/compose/binaries/main")


def run(*args, cwd=None):
    return subprocess.check_output(args, cwd=cwd, text=True, encoding="utf-8").strip()


def metadata():
    manifest = json.loads(Path("update/manifest.json").read_text(encoding="utf-8"))
    if type(manifest["versionCode"]) is not int or not 1 <= manifest["versionCode"] <= 65535:
        raise ValueError("Invalid native versionCode")
    for key in ("channel", "versionName"):
        if not re.fullmatch(r"[a-zA-Z0-9._-]+", manifest[key]):
            raise ValueError(f"Invalid {key}")
    notes = Path(f"update/changelogs/{manifest['versionCode']}.changelog").read_text(encoding="utf-8")
    if not notes.strip():
        raise ValueError("Empty changelog")
    return manifest, notes


def digest(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def validate_package(path, kind):
    if not path.is_file() or path.stat().st_size < 8:
        raise ValueError(f"Missing/empty package: {path}")
    with path.open("rb") as stream:
        header = stream.read(8)
        if kind == "dmg":
            if path.stat().st_size < 512:
                raise ValueError("Truncated DMG")
            stream.seek(-512, 2)
            valid = stream.read(4) == b"koly"
        else:
            valid = header.startswith({"msi": bytes.fromhex("d0cf11e0a1b11ae1"),
                                       "deb": b"!<arch>\n", "rpm": bytes.fromhex("edabeedb")}[kind])
    if not valid:
        raise ValueError(f"Invalid {kind} package header: {path}")


def collect(os_name, abi):
    actual_os = {"Windows": "windows", "Darwin": "macos", "Linux": "linux"}.get(platform.system())
    actual_abi = {"amd64": "x86_64", "x86_64": "x86_64", "aarch64": "arm64", "arm64": "arm64"}.get(platform.machine().lower())
    if (os_name, abi) != (actual_os, actual_abi):
        raise ValueError("Runner does not match target platform/architecture")
    manifest, _ = metadata()
    OUTPUT.mkdir(parents=True, exist_ok=True)
    assets = []
    for target_os, target_abi, kind in sorted(EXPECTED):
        if (target_os, target_abi) != (os_name, abi):
            continue
        found = list((PACKAGES / kind).glob(f"*.{kind}"))
        if len(found) != 1:
            raise ValueError(f"Expected one {kind} package, got {found}")
        validate_package(found[0], kind)
        name = f"yamibo-{manifest['channel']}-v{manifest['versionName']}-{os_name}-{abi}.{kind}"
        dest = OUTPUT / name
        shutil.copyfile(found[0], dest)
        assets.append(dict(platform=os_name, abi=abi, type=kind, fileName=name,
                           sha256=digest(dest), size=dest.stat().st_size))
    if not assets:
        raise ValueError("Unsupported package target")
    (OUTPUT / f"{os_name}-{abi}.json").write_text(json.dumps(assets, indent=2) + "\n", encoding="utf-8")


def load_assets(directory):
    assets = []
    for descriptor in sorted(directory.glob("*.json")):
        assets.extend(json.loads(descriptor.read_text(encoding="utf-8")))
    identities = [(a["platform"], a["abi"], a["type"]) for a in assets]
    if len(identities) != len(EXPECTED) or set(identities) != EXPECTED:
        raise ValueError("Desktop package set is incomplete or duplicated")
    names = set()
    for asset in assets:
        name = asset["fileName"]
        if not re.fullmatch(r"[a-zA-Z0-9._-]+", name) or name in names or not name.endswith("." + asset["type"]):
            raise ValueError("Invalid/duplicate package filename")
        names.add(name)
        path = directory / name
        validate_package(path, asset["type"])
        if path.stat().st_size != asset["size"] or digest(path) != asset["sha256"]:
            raise ValueError(f"Checksum/size mismatch: {name}")
    return sorted(assets, key=lambda a: a["fileName"])


def generate_manifest(source, notes, assets):
    for asset in assets:
        expected_name = f"yamibo-{source['channel']}-v{source['versionName']}-{asset['platform']}-{asset['abi']}.{asset['type']}"
        if asset["fileName"] != expected_name:
            raise ValueError("Package filename does not match source version")
    result = dict(source)
    base = f"https://github.com/{REPO}/releases"
    result.update(isReady=True, releaseNotes=notes, releaseUrl=f"{base}/tag/{source['versionCode']}")
    result["assets"] = [dict(a, url=f"{base}/download/{source['versionCode']}/{a['fileName']}") for a in assets]
    return result


def check_previous(previous, current):
    if previous and previous["versionCode"] > current["versionCode"]:
        raise ValueError("Refusing desktop feed downgrade")
    if previous and previous["versionCode"] == current["versionCode"]:
        if previous["versionName"] != current["versionName"]:
            raise ValueError("Published versionCode cannot be reused for another version")
        return True
    return False


def verify_remote(release, assets, tag):
    remote = {a["name"]: a for a in release["assets"]}
    for asset in assets:
        item = remote.get(asset["fileName"])
        if item is None or item["size"] != asset["size"]:
            raise ValueError(f"Remote package missing/wrong size: {asset['fileName']}")
        expected = "sha256:" + asset["sha256"]
        if item.get("digest"):
            if item["digest"] != expected:
                raise ValueError("Remote package digest mismatch")
        else:
            with tempfile.TemporaryDirectory() as temp:
                run("gh", "release", "download", tag, "--repo", REPO, "--pattern", asset["fileName"], "--dir", temp)
                if digest(Path(temp) / asset["fileName"]) != asset["sha256"]:
                    raise ValueError("Downloaded remote package digest mismatch")


def publish():
    source, notes = metadata()
    assets = load_assets(OUTPUT)  # All targets validated before any external mutation.
    manifest = generate_manifest(source, notes, assets)
    tag = str(source["versionCode"])
    head = run("git", "rev-parse", "HEAD")
    commit = json.loads(run("gh", "api", f"repos/{REPO}/commits/{tag}"))["sha"]
    if commit != head:
        raise ValueError("Shared release tag does not match this source commit")
    endpoint = f"repos/{REPO}/releases/tags/{tag}"
    release = json.loads(run("gh", "api", endpoint))
    if release["draft"]:
        raise ValueError("Shared Android release must be published first")
    remote = f"https://github.com/{REPO}.git"
    with tempfile.TemporaryDirectory(prefix="yamibo-feed-") as temp:
        feed = Path(temp)
        if run("git", "ls-remote", "--heads", remote, FEED):
            run("git", "clone", "--depth", "1", "--branch", FEED, remote, temp)
        else:
            run("git", "init", "-b", FEED, temp)
            run("git", "remote", "add", "origin", remote, cwd=temp)
        stable = feed / "update/stable.json"
        previous = json.loads(stable.read_text(encoding="utf-8")) if stable.exists() else None
        if check_previous(previous, source):
            # Rebuilt installers need not be byte-reproducible: preserve the already-published set.
            verify_remote(release, previous["assets"], tag)
            print("Desktop version already published and verified; unchanged.")
            return
        for asset in assets:
            run("gh", "release", "upload", tag, str(OUTPUT / asset["fileName"]), "--repo", REPO, "--clobber")
        verify_remote(json.loads(run("gh", "api", endpoint)), assets, tag)
        stable.parent.mkdir(parents=True, exist_ok=True)
        text = json.dumps(manifest, ensure_ascii=False, indent=2) + "\n"
        stable.write_text(text, encoding="utf-8")
        (stable.parent / "manifest.json").write_text(text, encoding="utf-8")
        changelogs = stable.parent / "changelogs"
        changelogs.mkdir(exist_ok=True)
        (changelogs / f"{tag}.changelog").write_text(notes, encoding="utf-8")
        checksums = OUTPUT / "desktop-SHA256SUMS.txt"
        checksums.write_text("".join(f"{a['sha256']}  {a['fileName']}\n" for a in assets), encoding="utf-8")
        run("gh", "release", "upload", tag, str(checksums), "--repo", REPO, "--clobber")
        run("git", "config", "user.name", "github-actions[bot]", cwd=temp)
        run("git", "config", "user.email", "github-actions[bot]@users.noreply.github.com", cwd=temp)
        run("git", "add", "update", cwd=temp)
        run("git", "commit", "-m", f"Publish desktop update {tag}", cwd=temp)
        # No force push: conflicting/newer publication cannot be silently overwritten.
        run("git", "push", "origin", FEED, cwd=temp)


def smoke():
    relative = {"Windows": "app/Yamibo/Yamibo.exe", "Darwin": "app/Yamibo.app/Contents/MacOS/Yamibo",
                "Linux": "app/Yamibo/bin/Yamibo"}[platform.system()]
    launcher = (PACKAGES / relative).resolve()
    result = subprocess.run([str(launcher), "--package-smoke-test"], capture_output=True, text=True,
                            encoding="utf-8", errors="replace", timeout=120)
    print(result.stdout)
    print(result.stderr)
    if result.returncode or "YAMIBO_PACKAGE_SMOKE_OK" not in result.stdout:
        raise ValueError("Packaged runtime/browser smoke test failed")


if __name__ == "__main__":
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    sys.stderr.reconfigure(encoding="utf-8", errors="replace")
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=["collect", "publish", "smoke"])
    parser.add_argument("--platform", choices=["windows", "macos", "linux"])
    parser.add_argument("--abi", choices=["x86_64", "arm64"])
    args = parser.parse_args()
    if args.action == "collect":
        collect(args.platform, args.abi)
    elif args.action == "publish":
        publish()
    else:
        smoke()
