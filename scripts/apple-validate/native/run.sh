#!/usr/bin/env bash
set -euo pipefail

# This permanent baseline compiles Kotlin for Simulator and device. It runs no tests.
if [ "$(uname -m)" != arm64 ]; then
  echo 'The native baseline requires an arm64 Mac for iosSimulatorArm64.' >&2
  exit 2
fi
chmod +x gradlew
./gradlew :composeApp:compileKotlinIosSimulatorArm64 :composeApp:compileKotlinIosArm64 \
  --console=plain --max-workers=2
printf 'PASS: Kotlin Simulator/device compilation. No unit tests or UI flows were run.\n' \
  > "$APPLE_VALIDATE_OUTPUT/RESULTS.md"
