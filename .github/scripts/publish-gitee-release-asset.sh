#!/usr/bin/env bash
set -euo pipefail

: "${GITEE_TOKEN:?GITEE_TOKEN is required}"
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

body="$(cat "$CHANGELOG")"
api="https://gitee.com/api/v5/repos/${MIRROR_OWNER}/${MIRROR_REPO}"
release_url="https://gitee.com/${MIRROR_OWNER}/${MIRROR_REPO}/releases/tag/${TAG}"
fallback_asset_url="https://gitee.com/${MIRROR_OWNER}/${MIRROR_REPO}/releases/download/${TAG}/${APK_NAME}"
curl_opts=(
  --silent
  --show-error
  --fail-with-body
  --connect-timeout 60
  --max-time 180
  --retry 2
  --retry-delay 5
  --retry-max-time 180
  --retry-connrefused
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

release_json="$(curl "${curl_opts[@]}" "${api}/releases/tags/${TAG}?access_token=${GITEE_TOKEN}" || true)"
release_id="$(printf '%s' "$release_json" | json_field id)"

if [ -n "$release_id" ]; then
  curl "${curl_opts[@]}" -X DELETE "${api}/releases/${release_id}" \
    -d "access_token=${GITEE_TOKEN}" \
    >/dev/null
fi

release_json="$(curl "${curl_opts[@]}" -X POST "${api}/releases" \
  -d "access_token=${GITEE_TOKEN}" \
  -d "tag_name=${TAG}" \
  -d "target_commitish=main" \
  --data-urlencode "name=${TITLE}" \
  --data-urlencode "body=${body}")"
release_id="$(printf '%s' "$release_json" | json_field id)"

if [ -z "$release_id" ]; then
  echo "Failed to create Gitee release: $release_json" >&2
  exit 1
fi

echo "Uploading ${APK_NAME} ($(stat -c%s "$APK") bytes) to Gitee..." >&2
upload_json="$(curl "${upload_curl_opts[@]}" \
  2> >(python3 .github/scripts/filter-upload-progress.py "Gitee upload") \
  -X POST "${api}/releases/${release_id}/attach_files" \
  -F "access_token=${GITEE_TOKEN}" \
  -F "file=@${APK}")"
echo "Gitee upload request completed." >&2
release_json="$(curl "${curl_opts[@]}" "${api}/releases/tags/${TAG}?access_token=${GITEE_TOKEN}" || true)"
asset_url="$(
  {
    printf '%s\n' "$upload_json"
    printf '%s\n' "$release_json"
  } | python3 .github/scripts/extract-release-asset-url.py "$APK_NAME" "$fallback_asset_url"
)"

echo "release_url=$release_url" >> "$GITHUB_OUTPUT"
echo "asset_url=$asset_url" >> "$GITHUB_OUTPUT"
