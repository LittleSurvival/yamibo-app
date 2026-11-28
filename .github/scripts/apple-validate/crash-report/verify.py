"""Inspect independently observed process, report, clipboard and UI lifecycle evidence."""
import json
import os
from pathlib import Path
import shutil
import sys
import time

phase, evidence_path, data_path, argument = sys.argv[1:]
out = Path(evidence_path)
data = Path(data_path)
report = data / "Library/Application Support/CrashReports/kotlin.json"

if phase == "crash":
    pid = int((out / "launch.txt").read_text().strip().split(": ")[-1])
    for _ in range(150):
        try:
            os.kill(pid, 0)
        except ProcessLookupError:
            if report.exists():
                break
        time.sleep(0.2)
    else:
        raise RuntimeError(f"Did not observe process {pid} exit and create {report}")
    shutil.copy2(report, out / "report.json")
    captured = json.loads(report.read_bytes())
    assert argument not in json.dumps(captured), "Private exception message saved"
    assert f"PID: {pid}\n" in captured["text"], captured
    assert "Source: Kotlin/Native" in captured["text"], captured
    assert "IllegalStateException" in captured["text"], captured
    stack = captured["text"].split("IllegalStateException", 1)[-1]
    assert any(ch.isdigit() for ch in stack) and len(stack.strip()) > 40, stack
    assert captured["id"] and captured["date"] and captured["metadata"], captured
    # A nonempty directory prevents the production atomic viewed.json write.
    obstacle = report.with_name("viewed.json")
    obstacle.mkdir()
    (obstacle / "qa-obstacle").touch()
    print(f"Observed fatal Kotlin exception in pid {pid} with private message removed")
elif phase == "recovery":
    captured = json.loads((out / "report.json").read_bytes())
    clipboard = (out / "clipboard.txt").read_bytes()
    assert clipboard == captured["text"].encode("utf-8"), "Clipboard differs from original report"
    before = float((Path(argument) / "tmp" / ("before-dismiss-" + out.name)).read_text())
    recovery = float((data / "tmp/normal-host-recovery").read_text())
    relaunch = float((data / "tmp/normal-host-relaunch").read_text())
    assert before <= recovery <= relaunch, (before, recovery, relaunch)
    print(f"Exact {len(clipboard)} clipboard bytes; before dismissal={before}, "
          f"first normal controller={recovery}, next launch={relaunch}")
else:
    raise ValueError(phase)
