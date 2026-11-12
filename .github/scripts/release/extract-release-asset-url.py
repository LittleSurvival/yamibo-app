#!/usr/bin/env python3
import json
import sys


def iter_values(value):
    if isinstance(value, dict):
        for child in value.values():
            yield from iter_values(child)
    elif isinstance(value, list):
        for child in value:
            yield from iter_values(child)
    elif isinstance(value, str):
        yield value


def main():
    if len(sys.argv) < 2:
        raise SystemExit("usage: extract-release-asset-url.py <asset-name> [fallback-url]")

    asset_name = sys.argv[1]
    fallback = sys.argv[2] if len(sys.argv) > 2 else ""
    raw = sys.stdin.read().strip()
    if not raw:
        print(fallback)
        return

    documents = []
    try:
        documents.append(json.loads(raw))
    except json.JSONDecodeError:
        for line in raw.splitlines():
            line = line.strip()
            if not line:
                continue
            try:
                documents.append(json.loads(line))
            except json.JSONDecodeError:
                continue
    if not documents:
        print(fallback)
        return

    candidates = []
    for data in documents:
        for value in iter_values(data):
            if asset_name in value and (value.startswith("http://") or value.startswith("https://")):
                candidates.append(value)

    for value in candidates:
        lowered = value.lower()
        if "download" in lowered or "attach" in lowered or "asset" in lowered or "release" in lowered:
            print(value)
            return

    if candidates:
        print(candidates[0])
    else:
        print(fallback)


if __name__ == "__main__":
    main()
