#!/usr/bin/env bash
set -euo pipefail
bash "$(dirname "$0")/stable-v0.1.0-forum-reading-modes-d211f9a-9b0e8d3-validation-01.sh"
exec bash "$(dirname "$0")/reader-modes/run_perf.sh" --long-image --perf Comic cold 1 Debug
