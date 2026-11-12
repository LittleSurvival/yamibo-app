#!/usr/bin/env bash
# Replace one public feed directory, retaining every other directory and Git history.
set -euo pipefail
remote="$1"
branch="$2"
source_dir="$3"
target="$4"
case "$target" in update|status) ;; *) echo "Unsupported feed directory" >&2; exit 1 ;; esac
source_dir="$(cd "$source_dir" && pwd)"
work="$(mktemp -d)"
trap 'rm -rf -- "$work"' EXIT

for attempt in 1 2 3; do
  dir="$work/attempt-$attempt"
  if ! timeout 60s git clone --quiet --depth 1 --single-branch --branch "$branch" "$remote" "$dir"; then
    # Bootstrap only an absent branch, never mistake a network failure for an empty repo.
    heads="$(timeout 60s git ls-remote --heads "$remote" "refs/heads/$branch")" || exit 1
    if [ -n "$heads" ]; then
      [ "$attempt" -lt 3 ] && sleep "$((attempt * 5))" && continue
      exit 1
    fi
    mkdir -p "$dir"
    git -C "$dir" init --quiet -b "$branch"
    git -C "$dir" remote add origin "$remote"
  fi
  git -C "$dir" config user.name "github-actions[bot]"
  git -C "$dir" config user.email "github-actions[bot]@users.noreply.github.com"
  if [ "$target" = status ] && [ -f "$dir/status/forum-524.json" ]; then
    python3 - "$source_dir/forum-524.json" "$dir/status/forum-524.json" <<'PY'
import json, sys
with open(sys.argv[1], encoding="utf-8") as f: incoming = json.load(f)
with open(sys.argv[2], encoding="utf-8") as f: current = json.load(f)
if incoming["revision"] < current["revision"]:
    raise SystemExit("Refusing to overwrite a newer server notice")
if incoming["revision"] == current["revision"] and incoming != current:
    raise SystemExit("Notice revision already published with different contents")
PY
  fi
  rm -rf -- "$dir/$target"
  cp -R "$source_dir" "$dir/$target"
  git -C "$dir" add -- "$target"
  if git -C "$dir" diff --cached --quiet; then exit 0; fi
  git -C "$dir" commit --quiet -m "Publish $target feed"
  if timeout 60s git -C "$dir" push --quiet origin "HEAD:refs/heads/$branch"; then exit 0; fi
  if [ "$attempt" -lt 3 ]; then sleep "$((attempt * 5))"; fi
done
echo "Feed publication failed after 3 attempts" >&2
exit 1
