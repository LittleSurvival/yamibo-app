#!/usr/bin/env bash
set -euo pipefail
export GRADLE_OPTS="${GRADLE_OPTS:-} -Dorg.gradle.jvmargs=-Xmx6g -Dorg.gradle.workers.max=2"

cd "$(git rev-parse --show-toplevel)"
bash .github/scripts/release/build-unsigned-ios-ipa.sh
cp "$RUNNER_TEMP"/ios-release/*.ipa "$APPLE_VALIDATE_OUTPUT/"
