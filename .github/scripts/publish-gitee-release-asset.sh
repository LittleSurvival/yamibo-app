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
transport_curl_opts=(
  --silent
  --show-error
  --connect-timeout 120
  --retry 2
  --retry-delay 5
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
  --connect-timeout 120
  --retry 2
  --retry-delay 5
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
    "${api}/releases/tags/${TAG}?access_token=${GITEE_TOKEN}")"; then
    echo "Failed to query existing Gitee release" >&2
    return 1
  fi
  status="${response##*$'\n'}"
  body="${response%$'\n'*}"
  case "$status" in
    200) printf '%s' "$body" ;;
    404) return 0 ;;
    *)
      echo "Gitee release lookup returned HTTP ${status}" >&2
      return 1
      ;;
  esac
}

release_json="$(lookup_release)"
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
release_json="$(lookup_release)"
asset_url="$(
  {
    printf '%s\n' "$upload_json"
    printf '%s\n' "$release_json"
  } | python3 .github/scripts/extract-release-asset-url.py "$APK_NAME" "$fallback_asset_url"
)"

if [ -z "$asset_url" ]; then
  echo "Gitee upload did not produce an asset URL" >&2
  exit 1
fi

apk_size="$(stat -c%s "$APK")"
headers_file="$RUNNER_TEMP/gitee-apk-headers.txt"
verify_gitee_asset() {
  local attempt
  for attempt in 1 2 3; do
    if curl --silent --show-error --fail-with-body \
      --location \
      --head \
      --connect-timeout 30 \
      --max-time 60 \
      --dump-header "$headers_file" \
      --output /dev/null \
      "$asset_url" && python3 .github/scripts/validate-release-asset-headers.py \
        "$headers_file" "$APK_NAME" "$apk_size"; then
      return 0
    fi
    if [ "$attempt" -lt 3 ]; then
      echo "Gitee asset is not ready (attempt ${attempt}/3); retrying in 5s..." >&2
      sleep 5
    fi
  done
  echo "Gitee asset validation failed after 3 attempts" >&2
  return 1
}

verify_gitee_asset

echo "release_url=$release_url" >> "$GITHUB_OUTPUT"
echo "asset_url=$asset_url" >> "$GITHUB_OUTPUT"
