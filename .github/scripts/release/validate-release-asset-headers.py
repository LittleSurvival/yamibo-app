#!/usr/bin/env python3
"""Validate the final response headers for a published APK asset."""

from pathlib import Path
import sys


ALLOWED_CONTENT_TYPES = {
    "application/octet-stream",
    "application/vnd.android.package-archive",
    "application/zip",
}


def final_response_headers(raw_headers: str) -> tuple[str, dict[str, str]]:
    blocks = raw_headers.replace("\r\n", "\n").split("\n\n")
    responses: list[tuple[str, dict[str, str]]] = []
    for block in blocks:
        lines = [line for line in block.splitlines() if line]
        if not lines or not lines[0].startswith("HTTP/"):
            continue
        headers: dict[str, str] = {}
        for line in lines[1:]:
            if ":" not in line:
                continue
            name, value = line.split(":", 1)
            headers[name.strip().lower()] = value.strip()
        responses.append((lines[0], headers))
    if not responses:
        raise ValueError("No HTTP response headers were captured")
    return responses[-1]


def validate(headers_path: Path, asset_name: str, expected_size: int) -> None:
    status_line, headers = final_response_headers(
        headers_path.read_text(encoding="iso-8859-1")
    )
    status_parts = status_line.split()
    if len(status_parts) < 2 or status_parts[1] != "200":
        raise ValueError(f"Final asset response was not HTTP 200: {status_line}")

    content_length = headers.get("content-length", "")
    try:
        actual_size = int(content_length)
    except ValueError as error:
        raise ValueError(f"Invalid Content-Length: {content_length!r}") from error
    if actual_size != expected_size:
        raise ValueError(
            f"Asset size mismatch: expected {expected_size}, got {actual_size}"
        )

    disposition = headers.get("content-disposition", "")
    if asset_name not in disposition:
        raise ValueError(
            f"Content-Disposition does not identify {asset_name!r}: {disposition!r}"
        )

    content_type = headers.get("content-type", "").split(";", 1)[0].strip().lower()
    if content_type not in ALLOWED_CONTENT_TYPES:
        raise ValueError(f"Unexpected APK Content-Type: {content_type!r}")


def main() -> None:
    if len(sys.argv) != 4:
        raise SystemExit(
            "usage: validate-release-asset-headers.py "
            "<headers-file> <asset-name> <expected-size>"
        )
    try:
        validate(Path(sys.argv[1]), sys.argv[2], int(sys.argv[3]))
    except (OSError, ValueError) as error:
        raise SystemExit(str(error)) from error


if __name__ == "__main__":
    main()
