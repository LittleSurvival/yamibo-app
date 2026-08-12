#!/usr/bin/env bash
set -euo pipefail

: "${GITEA_TOKEN:?GITEA_TOKEN is required}"
: "${MIRROR_OWNER:?MIRROR_OWNER is required}"
: "${MIRROR_REPO:?MIRROR_REPO is required}"
: "${TAG:?TAG is required}"
: "${TITLE:?TITLE is required}"
: "${CHANGELOG:?CHANGELOG is required}"
: "${APK:?APK is required}"
: "${APK_NAME:?APK_NAME is required}"

if [ ! -s "$CHANGELOG" ]; then
  echo "Changelog is missing or empty: $CHANGELOG" >&2
  exit 1
fi

gitea_base="${GITEA_BASE_URL:-https://gitea.com}"
gitea_base="${gitea_base%/}"
api="${gitea_base}/api/v1/repos/${MIRROR_OWNER}/${MIRROR_REPO}"
release_url="${gitea_base}/${MIRROR_OWNER}/${MIRROR_REPO}/releases/tag/${TAG}"
encoded_apk_name="$(python3 -c 'import sys, urllib.parse; print(urllib.parse.quote(sys.argv[1], safe=""))' "$APK_NAME")"

auth_header="Authorization: token ${GITEA_TOKEN}"
transport_curl_opts=(
  --silent
  --show-error
  --connect-timeout 60
  --max-time 180
  --retry 2
  --retry-delay 5
  --retry-max-time 180
  --retry-connrefused
)
curl_opts=(
  "${transport_curl_opts[@]}"
  --fail-with-body
)
upload_curl_opts=(
  --show-error
  --fail-with-body
  --progress-bar
  --connect-timeout 60
  --max-time 180
  --retry 2
  --retry-delay 5
  --retry-max-time 180
  --retry-connrefused
)

json_field() {
  local field="$1"
  python3 -c 'import json,sys; data=json.load(sys.stdin); print(data.get(sys.argv[1],""))' "$field" 2>/dev/null || true
}

lookup_release() {
  local response status body
  if ! response="$(curl "${transport_curl_opts[@]}" \
    --write-out $'\n%{http_code}' \
    -H "$auth_header" \
    "${api}/releases/tags/${TAG}")"; then
    echo "Failed to query existing Gitea release" >&2
    return 1
  fi
  status="${response##*$'\n'}"
  body="${response%$'\n'*}"
  case "$status" in
    200) printf '%s' "$body" ;;
    404) return 0 ;;
    *)
      echo "Gitea release lookup returned HTTP ${status}" >&2
      return 1
      ;;
  esac
}

release_json="$(lookup_release)"
release_id="$(printf '%s' "$release_json" | json_field id)"

if [ -n "$release_id" ]; then
  curl "${curl_opts[@]}" -X DELETE -H "$auth_header" "${api}/releases/${release_id}" >/dev/null
fi

body_file="$RUNNER_TEMP/gitea-release-body.json"
python3 - "$TAG" "$TITLE" "$CHANGELOG" "$body_file" <<'PY'
import json
import sys
from pathlib import Path

tag, title, changelog, output = sys.argv[1:5]
body = Path(changelog).read_text(encoding="utf-8")
payload = {
    "tag_name": tag,
    "target_commitish": "main",
    "name": title,
    "body": body,
    "draft": False,
    "prerelease": False,
}
Path(output).write_text(json.dumps(payload, ensure_ascii=False), encoding="utf-8")
PY

release_json="$(curl "${curl_opts[@]}" -X POST -H "$auth_header" -H "Content-Type: application/json" \
  --data-binary "@${body_file}" \
  "${api}/releases")"
release_id="$(printf '%s' "$release_json" | json_field id)"

if [ -z "$release_id" ]; then
  echo "Failed to create Gitea release: $release_json" >&2
  exit 1
fi

echo "Uploading ${APK_NAME} ($(stat -c%s "$APK") bytes) to Gitea..." >&2
upload_json="$(curl "${upload_curl_opts[@]}" \
  2> >(python3 .github/scripts/filter-upload-progress.py "Gitea upload") \
  -X POST -H "$auth_header" \
  -F "attachment=@${APK}" \
  "${api}/releases/${release_id}/assets?name=${encoded_apk_name}")"
echo "Gitea upload request completed." >&2
asset_url="$(
  printf '%s' "$upload_json" | python3 -c '
import json, sys
data = json.load(sys.stdin)
print(data.get("browser_download_url", ""))
'
)"

if [ -z "$asset_url" ]; then
  echo "Gitea upload response did not provide browser_download_url: $upload_json" >&2
  exit 1
fi

probe_file="$RUNNER_TEMP/gitea-apk-probe.bin"
verify_gitea_asset() {
  local attempt
  for attempt in 1 2 3; do
    if curl --silent --show-error --fail-with-body \
      --location \
      --connect-timeout 30 \
      --max-time 60 \
      --range 0-3 \
      --max-filesize 1024 \
      "$asset_url" \
      -o "$probe_file" && python3 - "$probe_file" <<'PY'
import sys
from pathlib import Path

header = Path(sys.argv[1]).read_bytes()[:4]
if header != b"PK\x03\x04":
    raise SystemExit(f"Gitea asset is not an APK/ZIP payload: {header!r}")
PY
    then
      return 0
    fi
    if [ "$attempt" -lt 3 ]; then
      echo "Gitea asset is not ready (attempt ${attempt}/3); retrying in 5s..." >&2
      sleep 5
    fi
  done
  echo "Gitea asset validation failed after 3 attempts" >&2
  return 1
}

verify_gitea_asset

echo "release_url=$release_url" >> "$GITHUB_OUTPUT"
echo "asset_url=$asset_url" >> "$GITHUB_OUTPUT"
