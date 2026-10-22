#!/usr/bin/env python3
"""Validate workflow inputs and generate one identical notice for all mirrors."""
import datetime as dt
import json
import os
from pathlib import Path
import re


def duration_minutes(value: str) -> int:
    if not re.fullmatch(r"\d{2}:\d{2}:\d{2}", value):
        raise ValueError("duration must use dd:hh:mm, for example 00:02:00")
    days, hours, minutes = map(int, value.split(":"))
    total = days * 1440 + hours * 60 + minutes
    if hours > 23 or minutes > 59 or total <= 0:
        raise ValueError("duration must be positive, with hh <= 23 and mm <= 59")
    return total


def make_notice(operation: str, message: str, duration: str, revision: int, now: int) -> dict:
    if operation not in {"publish", "cancel"}:
        raise ValueError("operation must be publish or cancel")
    if revision <= 0 or now <= 0:
        raise ValueError("revision and current time must be positive")
    enabled = operation == "publish"
    message = message.strip() if enabled else ""
    if enabled and (not message or len(message) > 1000):
        raise ValueError("message must contain between 1 and 1000 characters")
    minutes = duration_minutes(duration) if enabled else 0
    return dict(schemaVersion=1, httpStatus=524, revision=revision, enabled=enabled,
                startsAt=now, expiresAt=now + minutes * 60_000, message=message)


def main() -> None:
    # The workflow's sequence orders notices across runners and includes rerun attempts.
    revision = int(os.environ["GITHUB_RUN_NUMBER"]) * 1_000_000 + int(os.environ["GITHUB_RUN_ATTEMPT"])
    now = int(dt.datetime.now(dt.timezone.utc).timestamp() * 1000)
    notice = make_notice(os.environ["NOTICE_OPERATION"], os.environ.get("NOTICE_MESSAGE", ""),
                         os.environ.get("NOTICE_DURATION", ""), revision, now)
    output = Path(os.environ["NOTICE_OUTPUT_DIR"]) / "status" / "forum-524.json"
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(notice, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    summary = os.environ.get("GITHUB_STEP_SUMMARY")
    if summary:
        start = dt.datetime.fromtimestamp(notice["startsAt"] / 1000, dt.timezone.utc).isoformat()
        end = dt.datetime.fromtimestamp(notice["expiresAt"] / 1000, dt.timezone.utc).isoformat()
        # JSON quoting keeps multiline/user text separate from the operator summary.
        with open(summary, "a", encoding="utf-8") as stream:
            stream.write(f"### Server notice {revision}\n\n")
            stream.write(f"- Enabled: `{notice['enabled']}`\n- Start (UTC): `{start}`\n- End (UTC): `{end}`\n")
            stream.write("\n```json\n" + json.dumps(notice["message"], ensure_ascii=False) + "\n```\n")


if __name__ == "__main__":
    main()
