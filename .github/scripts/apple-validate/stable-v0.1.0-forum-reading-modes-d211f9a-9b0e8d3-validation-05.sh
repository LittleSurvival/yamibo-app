#!/usr/bin/env bash
set -euo pipefail
profile=.github/scripts/apple-validate/reader-modes
python3 -m venv "$RUNNER_TEMP/reader-perf-python"
py="$RUNNER_TEMP/reader-perf-python/bin/python"
"$py" -m pip -q install 'av>=14,<18' 'numpy>=2,<3'
"$py" "$profile/validate.py" --perf Comic cold 1 Debug
