#!/usr/bin/env bash
set -euo pipefail
exec bash "$(dirname "$0")/reader-modes/run_perf.sh" --prompt-input --perf Novel cold 10 Release
