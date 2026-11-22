"""Audit the required reader evidence. Inputs and reports are local QA artifacts."""
import argparse
import csv
from pathlib import Path
from itertools import product

PLATFORMS = ("android", "ios")
MODES = ("comic", "novel", "forum")
DIRECTIONS = (("comic", "novel"), ("novel", "comic"), ("comic", "forum"),
              ("forum", "comic"), ("novel", "forum"), ("forum", "novel"))
SCENARIOS = {
    "F07": "board search favorites history rss tag explicit-floor downloads board-defaults novel-detail continue",
    "F08": "fast-page fast-chapter back background reordered-writes scroll single previous next revisit mark-unread stop-before-commit stop-after-commit",
    "F09": "third-floor repeated-floor repeated-source across-pages moved-image removed-image inline lazy smiley missing retry",
    "F10": "fresh expired failed-refresh interrupted-pagination duplicate-page switched-account namesake rename reverse insert-earlier",
    "F11": "this-page full-thread current-chapter selected-chapters whole-work enqueue-twice simultaneous-sources cancel retry delete-task delete-one-ref delete-last-ref",
    "F12": "offline unauthorized expired-login rate-limit server-error timeout lost-storage out-of-space interrupted-before-switch restart repair-twice",
    "F13": "upgrade legacy-image-only partial upgrade-interrupted repeat-migration restore-on-android restore-on-ios clear-history delete-download remote-update old-client",
    "F14": "default-select default-partial default-details default-retry dark-select dark-partial dark-details dark-retry live-double-helix live-big-love live-decimal live-parts",
    "N01": "online-offline-library airplane-offline-library",
    "N02": "warm-open open-catalog reverse recreation ttl-minus-1ms",
    "N03": "known-page locate-pid locate-returns-body",
    "N04": "expired ttl-boundary blocked-search two-consumers cancel-one",
    "N05": "one-image one-page legacy-text repeated-repair",
    "N06": "downloaded fresh-local stale refresh",
    "N07": "visible-plus-prefetch rapid-switch return change-account saturated-old-prefetch large-image",
    "N08": "403 429 timeout redirect 304 retry-login cancellation",
    "P01": "warm-local process-cold",
    "P02": "cold-network slow-catalog fast-catalog",
    "P03": "entry interaction scrolling repeat-30",
    "P04": "58-chapters 1000-chapters zero-downloads 10000-downloads",
}
HEADER = ("case", "platform", "mode", "scenario", "status", "expected", "actual", "evidence")

def expected_cases():
    cases = []
    for platform in PLATFORMS:
        for i, (source, target) in enumerate(DIRECTIONS, 1):
            for path in ("offline-restart", "progress-roundtrip"):
                cases.append((f"F{i:02}", platform, source + "-to-" + target, path))
        for mode in MODES:
            cases.append(("F07", platform, mode, "same-mode-reopen"))
        for case, scenarios in SCENARIOS.items():
            modes = {"N02": ("comic",), "N04": ("comic",), "N06": ("novel",),
                     "N07": ("comic",)}.get(case, MODES if case in
                       ("N01", "N03", "P01", "P02", "P03", "P04") else ("shared",))
            for mode, scenario in product(modes, scenarios.split()):
                cases.append((case, platform, mode, scenario))
    return cases

def audit(path, root):
    expected = set(expected_cases())
    seen = set()
    problems = []
    passed = 0
    with path.open(encoding="utf-8-sig", newline="") as handle:
        reader = csv.DictReader(handle)
        if tuple(reader.fieldnames or ()) != HEADER:
            raise ValueError("invalid evidence columns")
        for number, row in enumerate(reader, 2):
            key = tuple(row[name] for name in HEADER[:4])
            if key in seen or key not in expected:
                problems.append(f"row {number} duplicate or unexpected {key}")
            seen.add(key)
            if row["status"] != "PASS":
                problems.append(f"{key} {row['status'] or 'UNTESTED'}")
                continue
            artifact = (root / row["evidence"]).resolve()
            if (not row["expected"].strip() or not row["actual"].strip() or
                    not artifact.is_relative_to(root.resolve()) or not artifact.is_file() or artifact.stat().st_size == 0):
                problems.append(f"row {number} missing expectations, observation, or evidence")
            else:
                passed += 1
    problems.extend(f"missing {key}" for key in sorted(expected - seen))
    return passed, problems

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("action", choices=("template", "audit"))
    parser.add_argument("csv", type=Path)
    parser.add_argument("--evidence-root", type=Path)
    args = parser.parse_args()
    if args.action == "template":
        args.csv.parent.mkdir(parents=True, exist_ok=True)
        with args.csv.open("x", encoding="utf-8", newline="") as handle:
            writer = csv.writer(handle)
            writer.writerow(HEADER)
            for key in expected_cases():
                writer.writerow((*key, "UNTESTED", "", "", ""))
        return
    passed, problems = audit(args.csv, args.evidence_root or args.csv.parent)
    print(f"{passed} documented passes / {len(expected_cases())} required rows")
    for problem in problems:
        print(problem)
    raise SystemExit(bool(problems))

if __name__ == "__main__":
    main()
