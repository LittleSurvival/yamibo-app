#!/usr/bin/env bash
set -euo pipefail

repo=$(git -C "$(dirname "$0")" rev-parse --show-toplevel)
if [[ -z ${APPLE_VALIDATE_OUTPUT:-} ]]; then
    output="$repo/.tmp/qa/apple-validate/results"
    mkdir -p "$output"
    APPLE_VALIDATE_OUTPUT=$(mktemp -d "$output/real-download.XXXXXX")
else
    mkdir -p "$APPLE_VALIDATE_OUTPUT"
    APPLE_VALIDATE_OUTPUT=$(cd "$APPLE_VALIDATE_OUTPUT" && pwd -P)
    if [[ -e "$APPLE_VALIDATE_OUTPUT/validation-commit.txt" || -e "$APPLE_VALIDATE_OUTPUT/real-download.xcresult" ]]; then
        echo 'Choose a fresh APPLE_VALIDATE_OUTPUT directory for this run.' >&2
        exit 1
    fi
fi
export APPLE_VALIDATE_OUTPUT
printf 'Validation artifacts: %s\n' "$APPLE_VALIDATE_OUTPUT"

scratch=$(mktemp -d "${TMPDIR:-/tmp}/yamibo-ios-download.XXXXXX")
trap 'rm -rf -- "$scratch"' EXIT
export RUNNER_TEMP="$scratch/tools"
export READER_PAIR_DERIVED="$scratch/derived"
unset READER_PERF READER_PAIRED_SIMULATOR READER_FRAMEWORK_RECEIPT PYTHONOPTIMIZE
mkdir -p "$scratch/source" "$RUNNER_TEMP"
revision=$(git -C "$repo" rev-parse --verify HEAD)
printf '%s\n' "$revision" > "$APPLE_VALIDATE_OUTPUT/validation-commit.txt"
git -C "$repo" archive "$revision" | tar -x -C "$scratch/source"
cd "$scratch/source"
python3 .github/scripts/apple-validate/reader-modes/validate.py --real-download
