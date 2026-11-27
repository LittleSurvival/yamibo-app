#!/usr/bin/env bash
set -euo pipefail
profile="$(dirname "$0")"
host_python="$(command -v python3.13 || command -v python3.12 || command -v python3.11 || command -v python3)"
"$host_python" --version
case " $* " in
  *" --tag-12 "*) "$host_python" "$profile/prepare_reference.py"; cd "$RUNNER_TEMP/reader-baseline-12"; profile=.github/scripts/apple-validate/reader-modes ;;
esac
"$host_python" -m venv "$RUNNER_TEMP/reader-perf-python"
py="$RUNNER_TEMP/reader-perf-python/bin/python"
"$py" -m pip -q install 'av>=14,<18' 'numpy==2.4.3' 'Pillow==12.1.1'
case " $* " in
  *" Novel "*|*" Forum "*) "$py" -m pip -q install 'opencv-python-headless>=4.13,<5' ;;
esac
"$py" "$profile/validate.py" "$@"
