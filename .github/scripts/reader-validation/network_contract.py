"""Check a reader recording against a *predeclared* HTTP attempt waterfall."""
import argparse
from collections import Counter
import json
from pathlib import Path

FIELDS = ("phase", "method", "resource", "status")

def signature(attempt):
    if any(key not in attempt for key in FIELDS):
        raise ValueError("incomplete attempt record")
    return tuple(attempt[k] for k in FIELDS)

def validate(contract, recording):
    errors = []
    if recording.get("observe_after_idle_ms", 0) < 5_000 or recording.get("pending") != 0:
        errors.append("incomplete observation window or unfinished request")
    if recording.get("fixture") != contract["fixture"]:
        errors.append("fixture mismatch")
    if recording.get("mode") != contract["mode"]:
        errors.append("mode mismatch")
    if recording.get("instrumented") != {"forum": True, "images": True, "downloader": True, "server": True}:
        errors.append("required observers missing")
    expected = Counter(map(signature, contract["attempts"]))
    client = recording["attempts"]
    actual = Counter(map(signature, client))
    if expected != actual:
        errors.append(f"unexpected {list((actual-expected).elements())}, missing {list((expected-actual).elements())}")
    ids = [r["id"] for r in client]
    if len(ids) != len(set(ids)):
        errors.append("duplicate client attempt id")
    sent = Counter((r["id"], r["method"], r["resource"], r["status"]) for r in client if r["sent"])
    server = Counter((r["id"], r["method"], r["resource"], r["status"]) for r in recording["server"])
    if sent != server:
        errors.append("transport and server attempts disagree")
    for r in client:
        if r["component"] not in ("forum", "images", "downloader") or not r.get("trigger"):
            errors.append("unattributed request")
        if r["elapsed_ms"] < 0 or r["bytes"] < 0:
            errors.append("invalid duration or byte count")
        if r["resource"].startswith("http") or "cookie" in r["resource"].lower():
            errors.append("expected anonymized fixture resource")
    for earlier, later in contract.get("order", []):
        ends = [r["start_ms"] + r["elapsed_ms"] for r in client if r["resource"] == earlier]
        starts = [r["start_ms"] for r in client if r["resource"] == later]
        if not ends or not starts or max(ends) > min(starts):
            errors.append(f"{later} started before {earlier} finished")
    return errors

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("contract", type=Path)
    parser.add_argument("recording", type=Path)
    args = parser.parse_args()
    errors = validate(*(json.loads(p.read_text(encoding="utf-8")) for p in (args.contract, args.recording)))
    print("\n".join(errors) if errors else "PASS")
    raise SystemExit(bool(errors))

if __name__ == "__main__":
    main()
