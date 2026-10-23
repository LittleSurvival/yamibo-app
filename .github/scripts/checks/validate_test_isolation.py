#!/usr/bin/env python3
"""Keep test/QA files and test-only build wiring off production branches."""

import argparse
from pathlib import PurePosixPath
import re
import subprocess
import sys


def git(*args: str) -> str:
    return subprocess.check_output(["git", *args], text=True, encoding="utf-8")


def is_test_path(path: str) -> bool:
    parts = PurePosixPath(path).parts
    name = parts[-1]
    if any(part.lower() in {
        "test", "tests", "testdata", "fixtures", "__tests__", "__snapshots__",
    } for part in parts[:-1]):
        return True
    if any(parts[i - 1] == "src" and part.lower().endswith("test")
           for i, part in enumerate(parts) if i):
        return True
    if re.search(
        r"(^test[_-].*\.(py|js|ts|sh|ps1)$"
        r"|(Test|Tests)\.(kt|java|swift)$"
        r"|\.(test|spec)\.[cm]?[jt]sx?$"
        r"|-qa\.ps1$"
        r"|(Qa|Fixture|Probe)Receiver\.kt$)", name,
    ):
        return True
    return path.startswith("tools/waf-405-simulator/") or path in {
        ".run/Stop WAF Debug.run.xml",
        ".run/composeApp WAF Debug.run.xml",
    }


def grep(ref: str | None, pattern: str, globs: list[str]) -> list[str]:
    args = ["git", "grep", "-n", "-E", "-e", pattern]
    args += [ref] if ref else ["--cached"]
    result = subprocess.run(
        args + ["--", *globs], text=True, encoding="utf-8", capture_output=True,
    )
    if result.returncode not in (0, 1):
        raise RuntimeError(result.stderr.strip())
    return result.stdout.splitlines()


def inspect(ref: str | None) -> list[str]:
    paths = (git("ls-tree", "-r", "--name-only", "-z", ref) if ref
             else git("ls-files", "-z")).split("\0")
    findings = [path for path in paths if path and is_test_path(path)]
    findings += grep(ref, (
        r"testImplementation|testRuntimeOnly|testCompileOnly|gradleTestKit"
        r"|commonTest|androidUnitTest|iosTest"
        r"|kotlin\(\"test"
    ), ["*.gradle.kts", "*.gradle"])
    findings += grep(ref, (
        r"org.jetbrains.kotlin:kotlin-test|kotlinx-coroutines-test"
        r"|io.ktor:ktor-client-mock|app.cash.sqldelight:sqlite-driver"
        r"|org.junit|org.testng"
    ), ["gradle/*.toml"])
    findings += grep(ref, (
        r"^[[:space:]]*import[[:space:]]+(kotlin\.test|org\.junit|org\.testng)"
    ), ["*.kt", "*.java"])
    findings += grep(ref, (
        r"(Qa|Fixture|Probe)Receiver"
    ), ["**/AndroidManifest.xml"])
    return findings


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base", help="Also inspect every new commit after this base")
    parser.add_argument("--ref", help="Inspect a committed tree instead of the Git index")
    args = parser.parse_args()
    try:
        findings = inspect(args.ref)
        if findings:
            print(f"[blocked] Test/QA content in {args.ref or 'the Git index'}:")
            print("\n".join(findings))
            return 1
        if args.base:
            tip = args.ref or "HEAD"
            # Check every introduced commit, not only the final diff: adding tests and
            # deleting them later would still ship the test files in PR history.
            for commit in git("rev-list", "--reverse", f"{args.base}..{tip}").splitlines():
                findings = inspect(commit)
                if findings:
                    print(f"[blocked] Test/QA content in introduced commit {commit}:")
                    print("\n".join(findings))
                    return 1
        print("[ok] No test/QA files or test-only build wiring in the checked production trees.")
        return 0
    except (OSError, subprocess.CalledProcessError, RuntimeError) as error:
        print(f"[error] {error}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
