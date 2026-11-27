#!/usr/bin/env bash
set -euo pipefail
exec bash "$(dirname "$0")/reader-modes/run_perf.sh" --tag-12 --export-framework --network fast --perf Forum cold 1 Release
