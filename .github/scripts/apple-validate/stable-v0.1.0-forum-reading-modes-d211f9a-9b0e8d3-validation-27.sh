#!/usr/bin/env bash
set -euo pipefail
exec bash "$(dirname "$0")/reader-modes/run_perf.sh" --tag-12 --network fast --perf Novel cold 10 Release
