#!/usr/bin/env python3
"""Turn curl's carriage-return progress meter into rate-limited CI log lines."""

import codecs
import os
import re
import sys
import time


class ProgressFilter:
    def __init__(self, label: str, report_interval: float = 5.0) -> None:
        self.label = label
        self.last_percent = -1.0
        self.last_report_at = 0.0
        self.report_interval = report_interval

    def consume(self, line: str) -> None:
        match = re.search(r"(\d+(?:\.\d+)?)%", line)
        if match:
            percent = float(match.group(1))
            if percent + 1 < self.last_percent:
                print(
                    f"{self.label}: retry progress reset",
                    file=sys.stderr,
                    flush=True,
                )
                self.last_report_at = 0.0
            now = time.monotonic()
            should_report = percent != self.last_percent and (
                self.last_report_at == 0.0
                or now - self.last_report_at >= self.report_interval
                or percent >= 100
            )
            if should_report:
                print(
                    f"{self.label}: {percent:5.1f}%",
                    file=sys.stderr,
                    flush=True,
                )
                self.last_report_at = now
            self.last_percent = percent
        elif line.startswith(("curl:", "Warning:", "Error:")):
            print(line, file=sys.stderr, flush=True)


def main() -> None:
    label = sys.argv[1] if len(sys.argv) > 1 else "Upload"
    progress_filter = ProgressFilter(label)
    decoder = codecs.getincrementaldecoder("utf-8")(errors="replace")
    pending = ""

    while chunk := os.read(sys.stdin.fileno(), 4096):
        pending += decoder.decode(chunk)
        parts = re.split(r"[\r\n]", pending)
        pending = parts.pop()
        for part in parts:
            progress_filter.consume(part)

    pending += decoder.decode(b"", final=True)
    if pending:
        progress_filter.consume(pending)


if __name__ == "__main__":
    main()
