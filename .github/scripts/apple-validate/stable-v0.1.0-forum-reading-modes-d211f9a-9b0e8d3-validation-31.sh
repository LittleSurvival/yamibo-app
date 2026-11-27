#!/usr/bin/env bash
set -euo pipefail
exec bash "$(dirname "$0")/reader-modes/run_perf.sh" --sustain --perf Comic warm 30 Release
