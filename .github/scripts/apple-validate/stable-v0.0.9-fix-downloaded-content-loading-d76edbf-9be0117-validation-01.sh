#!/usr/bin/env bash
set -euo pipefail

export APPLE_VALIDATE_OUTPUT="${APPLE_VALIDATE_OUTPUT:-$PWD/.tmp/qa/apple-validate/downloaded-content}"
mkdir -p "$APPLE_VALIDATE_OUTPUT"
exec python3 .github/scripts/apple-validate/downloaded-content/validate.py
