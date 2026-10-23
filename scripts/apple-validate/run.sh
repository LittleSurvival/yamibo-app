#!/usr/bin/env bash
set -euo pipefail

profile="${1:-}"
if [[ ! "$profile" =~ ^[a-z0-9]+(-[a-z0-9]+)*$ ]]; then
  echo 'Usage: bash scripts/apple-validate/run.sh <profile-name>' >&2
  exit 2
fi

repo_root="$(git rev-parse --show-toplevel)"
cd "$repo_root"
entry="scripts/apple-validate/$profile/run.sh"
if [ ! -f "$entry" ]; then
  echo "Profile not found at $entry in $(git rev-parse HEAD). Select the validation ref containing it." >&2
  exit 2
fi
if [ "$(uname -s)" != Darwin ]; then
  echo 'Apple validation requires macOS with Xcode and the selected Simulator runtime.' >&2
  exit 2
fi

export APPLE_VALIDATE_OUTPUT="${APPLE_VALIDATE_OUTPUT:-$repo_root/.tmp/qa/apple-validate/$profile}"
mkdir -p "$APPLE_VALIDATE_OUTPUT"
export APPLE_VALIDATE_OUTPUT="$(cd "$APPLE_VALIDATE_OUTPUT" && pwd)"
{
  printf 'Profile: %s\nCommit: %s\n' "$profile" "$(git rev-parse HEAD)"
  git status --short
  uname -m
  sw_vers
  java -version 2>&1
  xcodebuild -version
  xcrun --sdk iphonesimulator --show-sdk-version
  xcrun simctl list devices available
} | tee "$APPLE_VALIDATE_OUTPUT/environment.log"

# Keep the profile failure when tee succeeds. Do not infer test success from this log.
set +e
bash "$entry" 2>&1 | tee "$APPLE_VALIDATE_OUTPUT/validation.log"
status=$?
set -e
printf '%s\n' "$status" > "$APPLE_VALIDATE_OUTPUT/exit-code.txt"
exit "$status"
