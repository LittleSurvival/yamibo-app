#!/usr/bin/env bash
set -euo pipefail
exec python3 "$(dirname "$0")/reader-modes/paired_perf.py" Forum --control
