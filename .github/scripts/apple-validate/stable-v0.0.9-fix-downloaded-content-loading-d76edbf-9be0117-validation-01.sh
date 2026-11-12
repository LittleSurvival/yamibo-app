#!/usr/bin/env bash
set -euo pipefail

# Compile exact production trees, without the validation branch's QA adapters.
# This revision intentionally performs compile-only validation (no runtime tests).
export APPLE_VALIDATE_OUTPUT="${APPLE_VALIDATE_OUTPUT:-$PWD/.tmp/qa/apple-validate/production-compile}"
mkdir -p "$APPLE_VALIDATE_OUTPUT"
output="$(cd "$APPLE_VALIDATE_OUTPUT" && pwd)"
refs=(bda741ff3d75e2a7c8f2d02e91fdd828e01ef89a 688e92f1be3467cfd0ccbcd046dc1aca53cc39d9 92a201d3eae2c03afdd0ff7ebe68b91281e79d92 2a57604f5be9b5fe8018ee0f3ecc517c64c0ff50)
checkout="$(mktemp -d "${RUNNER_TEMP:-${TMPDIR:-/tmp}}/yamibo-production-compile.XXXXXX")"
git fetch --no-tags origin "${refs[@]}"
git worktree add --detach "$checkout" "${refs[0]}"
for ref in "${refs[@]}"; do
    git -C "$checkout" switch --detach "$ref"
    actual="$(git -C "$checkout" rev-parse HEAD)"
    test "$actual" = "$ref"
    git -C "$checkout" status --short > "$output/$ref-status.txt"
    test ! -s "$output/$ref-status.txt"
    echo "::group::Production iOS compilation $ref"
    (
        cd "$checkout"
        bash ./gradlew :composeApp:compileKotlinIosSimulatorArm64 :composeApp:compileKotlinIosArm64 \
            --console=plain --no-daemon --max-workers=2
    ) 2>&1 | tee "$output/$ref-compile.log"
    printf '%s PASS iosSimulatorArm64 iosArm64\n' "$ref" >> "$output/compile-results.txt"
    echo "::endgroup::"
done
