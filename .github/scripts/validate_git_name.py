#!/usr/bin/env python3
"""Validate the repository-local Git user name before committing.

When a non-TheNano identity is seen, the validator asks how to handle this
commit and then separately asks whether that choice should be saved. A saved
choice is stored only in this repository's local Git config.
"""

from __future__ import annotations

import argparse
import subprocess
import sys
from pathlib import Path


EXPECTED_NAME = "TheNano"
CONFIG_KEY = "yamibo.gitNameValidator.savedAction"
ACTION_FIX = "fix"
ACTION_ALLOW = "allow"
ACTION_BLOCK = "block"
VALID_ACTIONS = {ACTION_FIX, ACTION_ALLOW, ACTION_BLOCK}


class GitError(RuntimeError):
    pass


def run_git(repo: Path, *args: str, check: bool = True) -> subprocess.CompletedProcess[str]:
    result = subprocess.run(
        ["git", *args],
        cwd=repo,
        text=True,
        capture_output=True,
        check=False,
    )
    if check and result.returncode != 0:
        detail = result.stderr.strip() or result.stdout.strip() or "unknown Git error"
        raise GitError(detail)
    return result


def repository_root(start: Path) -> Path:
    result = run_git(start, "rev-parse", "--show-toplevel")
    return Path(result.stdout.strip()).resolve()


def saved_action(repo: Path) -> str | None:
    result = run_git(repo, "config", "--local", "--get", CONFIG_KEY, check=False)
    if result.returncode == 1:
        return None
    if result.returncode != 0:
        raise GitError(result.stderr.strip() or f"could not read {CONFIG_KEY}")
    action = result.stdout.strip().lower()
    return action if action in VALID_ACTIONS else None


def save_action(repo: Path, action: str) -> None:
    run_git(repo, "config", "--local", CONFIG_KEY, action)


def reset_saved_action(repo: Path) -> None:
    result = run_git(repo, "config", "--local", "--unset-all", CONFIG_KEY, check=False)
    if result.returncode not in {0, 1, 5}:
        raise GitError(result.stderr.strip() or f"could not reset {CONFIG_KEY}")


def ask_yes_no(prompt: str) -> bool:
    while True:
        try:
            answer = input(prompt).strip().lower()
        except (EOFError, KeyboardInterrupt):
            print("\n[blocked] No answer received; commit validation was cancelled.", file=sys.stderr)
            raise SystemExit(1)
        if answer in {"y", "yes", "是"}:
            return True
        if answer in {"", "n", "no", "否", "不是"}:
            return False
        print("Please answer yes/y/是 or no/n/否/不是.")


def ask_mismatch_action(current_name: str) -> str:
    print(f"Current Git user.name is {current_name!r}; expected {EXPECTED_NAME!r}.")
    print("  1. Set this repository's user.name to TheNano")
    print("  2. Allow this commit once")
    print("  3. Cancel the commit")
    while True:
        try:
            answer = input("Select an option [1-3]: ").strip()
        except (EOFError, KeyboardInterrupt):
            print("\n[blocked] No action selected; commit validation was cancelled.", file=sys.stderr)
            raise SystemExit(1)
        if answer == "1":
            return ACTION_FIX
        if answer == "2":
            return ACTION_ALLOW
        if answer == "3" or answer == "":
            return ACTION_BLOCK
        print("Please enter 1, 2, or 3.")


def apply_action(repo: Path, action: str, current_name: str, *, saved: bool) -> int:
    source = "saved choice" if saved else "current choice"
    if action == ACTION_FIX:
        run_git(repo, "config", "--local", "user.name", EXPECTED_NAME)
        print(f"[ok] {source}: repository-local Git user.name is now {EXPECTED_NAME}.")
        return 0
    if action == ACTION_ALLOW:
        print(f"[allowed] {source}: continuing with Git user.name {current_name!r}.")
        return 0
    print(f"[blocked] {source}: commit cancelled because Git user.name is {current_name!r}.", file=sys.stderr)
    return 1


def validate(repo: Path) -> int:
    current_name = run_git(repo, "config", "--get", "user.name", check=False).stdout.strip()
    if current_name == EXPECTED_NAME:
        print(f"[ok] Git user.name is {EXPECTED_NAME}.")
        return 0

    display_name = current_name or "<unset>"
    action = saved_action(repo)
    if action is not None:
        return apply_action(repo, action, display_name, saved=True)

    action = ask_mismatch_action(display_name)
    if ask_yes_no("Save this choice for future name mismatches in this repository? [y/N]: "):
        save_action(repo, action)
        print(f"[saved] Future mismatch action: {action} ({CONFIG_KEY}).")
    else:
        print("[not saved] The validator will ask again on the next mismatch.")
    return apply_action(repo, action, display_name, saved=False)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--repo",
        type=Path,
        default=Path.cwd(),
        help="path inside the Git repository (default: current directory)",
    )
    parser.add_argument(
        "--reset-saved-choice",
        action="store_true",
        help="forget the saved mismatch action for this repository",
    )
    parser.add_argument(
        "--show-saved-choice",
        action="store_true",
        help="print the saved mismatch action and exit",
    )
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    try:
        repo = repository_root(args.repo.resolve())
        if args.reset_saved_choice:
            reset_saved_action(repo)
            print(f"[ok] Reset saved repository choice {CONFIG_KEY}.")
            return 0
        if args.show_saved_choice:
            print(saved_action(repo) or "unset")
            return 0
        return validate(repo)
    except (GitError, OSError) as error:
        print(f"[error] {error}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
