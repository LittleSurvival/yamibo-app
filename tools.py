#!/usr/bin/env python3
"""Interactive cross-platform project maintenance tools.

Run from the project root with:

    python tools.py

The menu combines Codex ref cleanup with the existing Windows build-unlock
script. On Linux and macOS, build unlock uses lsof and the current user's
process permissions.
"""

from __future__ import annotations

import argparse
import hashlib
import os
import shutil
import signal
import subprocess
import sys
import time
from pathlib import Path


PROJECT_ROOT = Path(__file__).resolve().parent
ZERO_OID = "0" * 40
BUILD_FILE_SUFFIXES = {".jar", ".apk", ".dex", ".lock"}
IGNORED_PROCESS_NAMES = {
    "cmd",
    "explorer",
    "fish",
    "idea64",
    "powershell",
    "pwsh",
    "python",
    "python3",
    "sh",
    "studio64",
    "bash",
    "zsh",
}


class Style:
    """Small ANSI style helper with a plain-text fallback."""

    def __init__(self):
        pass

    enabled = sys.stdout.isatty() and os.environ.get("TERM", "") != "dumb"
    if os.name == "nt":
        os.system("")  # Enable virtual terminal sequences on common Windows consoles.

    RESET = "\033[0m" if enabled else ""
    BOLD = "\033[1m" if enabled else ""
    DIM = "\033[2m" if enabled else ""
    CYAN = "\033[36m" if enabled else ""
    GREEN = "\033[32m" if enabled else ""
    YELLOW = "\033[33m" if enabled else ""
    RED = "\033[31m" if enabled else ""


def say(message: str, color: str = "") -> None:
    print(f"{color}{message}{Style.RESET}")


def section(title: str) -> None:
    print()
    say(f"── {title} ", Style.CYAN + Style.BOLD)


def native_path(path: Path) -> str:
    """Return a Windows long-path form when needed."""
    raw = os.path.abspath(os.fspath(path))
    if os.name != "nt" or raw.startswith("\\\\?\\"):
        return raw
    if raw.startswith("\\\\"):
        return "\\\\?\\UNC\\" + raw[2:]
    return "\\\\?\\" + raw


def find_git_dir(repo: Path) -> Path:
    result = subprocess.run(
        ["git", "-C", str(repo), "rev-parse", "--git-dir"],
        check=True,
        capture_output=True,
        text=True,
    )
    git_path = Path(result.stdout.strip())
    if not git_path.is_absolute():
        git_path = repo / git_path
    return git_path.resolve()


def find_broken_codex_refs(git_path: Path) -> list[Path]:
    """Find only zero-valued loose refs under refs/codex."""
    refs_root = git_path / "refs" / "codex"
    if not os.path.isdir(native_path(refs_root)):
        return []

    broken: list[Path] = []
    for root, _dirs, files in os.walk(native_path(refs_root)):
        for filename in files:
            candidate = Path(root) / filename
            try:
                value = candidate.read_text(encoding="ascii").strip()
            except (OSError, UnicodeError):
                continue
            if value == ZERO_OID:
                broken.append(candidate)
    return broken


def quarantine_ref(ref_path: Path, git_path: Path) -> Path:
    """Move a broken ref to a short, recoverable location in .git."""
    refs_root = git_path / "refs" / "codex"
    relative = os.path.relpath(native_path(ref_path), native_path(refs_root))
    digest = hashlib.sha256(relative.encode("utf-8")).hexdigest()[:16]
    backup_dir = git_path / "codex-ref-backups"
    backup_dir.mkdir(exist_ok=True)
    destination = backup_dir / f"zero-ref-{digest}.txt"
    if destination.exists():
        raise FileExistsError(f"Backup already exists: {destination}")
    shutil.move(native_path(ref_path), native_path(destination))
    return destination


def cleanup_codex_refs(repo: Path) -> bool:
    section("Codex ref cleanup")
    try:
        git_path = find_git_dir(repo)
        refs = find_broken_codex_refs(git_path)
    except (OSError, subprocess.CalledProcessError) as error:
        say(f"[error] Unable to inspect Git metadata: {error}", Style.RED)
        return False

    if not refs:
        say("[ok] No broken Codex refs found.", Style.GREEN)
        return True

    say(f"[found] {len(refs)} broken ref(s):", Style.YELLOW)
    for ref in refs:
        print(f"  {ref}")

    if not confirm("Move these refs to a recoverable backup? [y/N] "):
        say("[skip] No refs were changed.", Style.DIM)
        return True

    try:
        for ref in refs:
            destination = quarantine_ref(ref, git_path)
            say(f"[moved] {destination}", Style.GREEN)
    except OSError as error:
        say(f"[error] Failed to quarantine a ref: {error}", Style.RED)
        return False

    say("[ok] Broken refs quarantined; branches and source files were not changed.", Style.GREEN)
    return True


def build_files(repo: Path) -> list[Path]:
    files: list[Path] = []
    for directory in repo.rglob("build"):
        if not directory.is_dir() or ".git" in directory.parts:
            continue
        for path in directory.rglob("*"):
            if path.is_file() and path.suffix.lower() in BUILD_FILE_SUFFIXES:
                files.append(path)
    return files


def process_name(pid: int) -> str:
    try:
        result = subprocess.run(
            ["ps", "-p", str(pid), "-o", "comm="],
            capture_output=True,
            text=True,
            check=False,
        )
        return Path(result.stdout.strip()).name or f"pid-{pid}"
    except OSError:
        return f"pid-{pid}"


def locking_pids(path: Path) -> set[int]:
    if shutil.which("lsof"):
        result = subprocess.run(
            ["lsof", "-t", "--", str(path)],
            capture_output=True,
            text=True,
            check=False,
        )
        return {int(value) for value in result.stdout.split() if value.isdigit()}

    if shutil.which("fuser"):
        result = subprocess.run(
            ["fuser", str(path)],
            capture_output=True,
            text=True,
            check=False,
        )
        return {
            int(value)
            for value in result.stdout.replace("stdout:", "").split()
            if value.rstrip("cC") .isdigit()
        }

    return set()


def unlock_windows(repo: Path) -> bool:
    script = repo / "unlock_build.ps1"
    shell = shutil.which("pwsh") or shutil.which("powershell")
    if not shell:
        say("[error] PowerShell was not found.", Style.RED)
        return False

    result = subprocess.run(
        [
            shell,
            "-NoProfile",
            "-ExecutionPolicy",
            "Bypass",
            "-File",
            str(script),
            "-TargetDir",
            str(repo),
        ],
        check=False,
    )
    return result.returncode == 0


def unlock_posix(repo: Path) -> bool:
    section("Build unlock")
    files = build_files(repo)
    if not files:
        say("[ok] No build artifacts found.", Style.GREEN)
        return True
    if not shutil.which("lsof") and not shutil.which("fuser"):
        say("[error] Requires lsof or fuser to inspect locked files.", Style.RED)
        return False

    locks: dict[int, set[str]] = {}
    for path in files:
        for pid in locking_pids(path):
            name = process_name(pid)
            if name.lower() not in IGNORED_PROCESS_NAMES:
                locks.setdefault(pid, set()).add(str(path))

    if not locks:
        say("[ok] No eligible processes are locking build artifacts.", Style.GREEN)
        return True

    say(f"[found] {len(locks)} process(es) holding build files:", Style.YELLOW)
    for pid, paths in locks.items():
        print(f"  PID {pid:<7} {process_name(pid)} ({len(paths)} file(s))")

    if not confirm("Terminate these processes to unlock the build? [y/N] "):
        say("[skip] No processes were terminated.", Style.DIM)
        return True

    failed = False
    for pid in locks:
        try:
            os.kill(pid, signal.SIGTERM)
            time.sleep(0.15)
            say(f"[killed] PID {pid}", Style.GREEN)
        except ProcessLookupError:
            say(f"[gone] PID {pid} already exited", Style.DIM)
        except OSError as error:
            say(f"[error] PID {pid}: {error}", Style.RED)
            failed = True
    return not failed


def unlock_build(repo: Path) -> bool:
    if os.name == "nt":
        section("Build unlock")
        return unlock_windows(repo)
    return unlock_posix(repo)


def confirm(prompt: str) -> bool:
    try:
        return input(prompt).strip().lower() in {"y", "yes"}
    except (EOFError, KeyboardInterrupt):
        print()
        return False


def wait_for_enter() -> None:
    try:
        input("\nPress Enter to return to the menu...")
    except (EOFError, KeyboardInterrupt):
        print()


def menu(repo: Path) -> None:
    while True:
        print()
        say("╭────────────────────────────────────────╮", Style.CYAN)
        say("│        Yamibo Project Tools             │", Style.CYAN + Style.BOLD)
        say("╰────────────────────────────────────────╯", Style.CYAN)
        print(f"  {Style.DIM}Project{Style.RESET}: {repo}")
        print()
        print("  1. Clean broken Codex refs")
        print("  2. Unlock build files")
        print("  3. Run both tools")
        print("  0. Exit")

        try:
            choice = input("\n  Select an option [0-3]: ").strip()
        except (EOFError, KeyboardInterrupt):
            print("\n")
            return

        if choice == "1":
            cleanup_codex_refs(repo)
            wait_for_enter()
        elif choice == "2":
            unlock_build(repo)
            wait_for_enter()
        elif choice == "3":
            cleanup_codex_refs(repo)
            unlock_build(repo)
            wait_for_enter()
        elif choice == "0":
            say("\nGoodbye.", Style.CYAN)
            return
        else:
            say("\n[error] Please enter 0, 1, 2, or 3.", Style.RED)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--repo",
        type=Path,
        default=PROJECT_ROOT,
        help="project root (default: directory containing tools.py)",
    )
    args = parser.parse_args()
    menu(args.repo.resolve())
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
