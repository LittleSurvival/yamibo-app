#!/usr/bin/env python3
"""Turn curl's carriage-return progress meter into rate-limited CI log lines."""

import re
import sys
import time


label = sys.argv[1] if len(sys.argv) > 1 else "Upload"
pending = ""
last_percent = -1.0
last_report_at = 0.0
report_interval = 5.0


def consume(line: str) -> None:
    global last_percent, last_report_at

    match = re.search(r"(\d+(?:\.\d+)?)%", line)
    if match:
        percent = float(match.group(1))
        if percent + 1 < last_percent:
            print(f"{label}: retry progress reset", file=sys.stderr, flush=True)
            last_report_at = 0.0
        now = time.monotonic()
        if (
            last_report_at == 0.0
            or now - last_report_at >= report_interval
            or percent >= 100
        ):
            print(f"{label}: {percent:5.1f}%", file=sys.stderr, flush=True)
            last_report_at = now
        last_percent = percent
    elif line.startswith(("curl:", "Warning:", "Error:")):
        print(line, file=sys.stderr, flush=True)


for chunk in iter(lambda: sys.stdin.read(4096), ""):
    pending += chunk
    parts = re.split(r"[\r\n]", pending)
    pending = parts.pop()
    for part in parts:
        consume(part)

if pending:
    consume(pending)
