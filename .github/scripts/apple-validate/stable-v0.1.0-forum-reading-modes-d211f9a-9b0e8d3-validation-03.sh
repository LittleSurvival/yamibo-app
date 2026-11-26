#!/usr/bin/env bash
set -euo pipefail
# Each direction starts with a seeded author-scoped download. XCTest verifies
# the second local image in the source, destination, warm return and cold
# reopen. Source downloading, airplane mode and network counts are unmeasured.
python3 .github/scripts/apple-validate/reader-modes/validate.py --matrix
