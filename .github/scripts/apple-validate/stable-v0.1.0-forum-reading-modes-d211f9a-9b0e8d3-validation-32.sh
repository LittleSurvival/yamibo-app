#!/usr/bin/env bash
set -euo pipefail
exec bash "$(dirname "$0")/reader-modes/run_perf.sh" --guard-local --perf Comic cold 2 Release
