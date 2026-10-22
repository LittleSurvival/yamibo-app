# Timed HTTP 524 notices

After installing an app version with `ServerNoticeRepository`, forum HTTP 524
errors can display a manually published notice without another app release.
The request still fails; retries, authentication, WAF recovery and job status are
unchanged. Other status codes, device timeouts, image hosts and WebView-rendered
website pages are not replaced.

## Publish or cancel

Run **Publish server notice** from GitHub Actions on `main`:

| Input | Publish | Cancel |
| --- | --- | --- |
| `operation` | `publish` | `cancel` |
| `message` | Plain text, 1–1000 characters | Ignored |
| `duration` | `dd:hh:mm`, positive; default `00:01:00` | Ignored |

`00:02:00` means two hours, `01:00:00` means one day. Hours must be 00–23 and
minutes 00–59. Validity starts when the preparation job generates the notice,
not when the run is queued. The summary records UTC start/end times and each
mirror's outcome. Queuing, raw-file caching and network failures mean publication
is not instantaneous for every device.

The workflow reuses `GITEE_SSH_PRIVATE_KEY`, `GITEA_SSH_PRIVATE_KEY`, optional
`GITEA_SSH_HOST` / `GITEA_SSH_PORT`, and GitHub's repository token. It publishes
the same document to:

- GitHub `LittleSurvival/yamibo-app`, branch `update-release`.
- Gitee `LittleSurvival/ymb-apk-release`, branch `main`.
- Gitea `LittleSurvival/ymb-apk-release`, branch `main`.

Each feed uses `status/forum-524.json`. App version feeds stay in `update/`.
Release, ready, sync, stop-update and notice workflows share the mirror lock;
directory publication preserves other files and avoids force-pushing branches.
Do not run an older revision of a publishing workflow: old force-push versions
can still erase the notice directory.

## Document contract

```json
{
  "schemaVersion": 1,
  "httpStatus": 524,
  "revision": 1000001,
  "enabled": true,
  "startsAt": 1792627200000,
  "expiresAt": 1792634400000,
  "message": "百合會正在設備升級"
}
```

Times are UTC Unix milliseconds. The interval includes `startsAt` and excludes
`expiresAt`, using the device clock. `revision` is the workflow run number times
1,000,000 plus the run attempt. Keep the workflow identity and revision sequence
when moving or recreating it. A rerun of an older run cannot overwrite a newer
notice. Repeating the latest run creates a new attempt and a fresh validity window.

Cancel publishes a newer document with `enabled: false`, an empty message and
equal start/end times. Keep that document: deleting it would lose the revocation
record. The app retains the newest valid revision even after expiry/cancellation
and rejects older mirrors. Equal revisions are immutable.

## Client fallback

Only a `YamiboResult.Failure` with the API's HTTP envelope, status 524 and an
HTTPS `bbs.yamibo.com` URL qualifies. The plain notice is resolved before adding
UI prefixes or truncating diagnostics. API result objects and stored job errors
remain available for normal failure handling.

The first qualifying error queries three sources concurrently, with an overall
three-second budget. UI and workers share an Android process cache; refreshes
are coalesced and throttled for sixty seconds using a monotonic clock. Expiry
uses wall time and is checked for every resolution. If refresh fails, an unexpired
cached notice can still be used; otherwise the original message is shown.
Nothing polls while no qualifying error is being presented. Already delivered
notifications are not recalled when a notice expires or is cancelled.

New installations cannot know about a revocation available only on unreachable
mirrors. During partial publication or caching delays, some clients may still
use an older unexpired notice. Check all three publication results, retry failed
mirrors, and use short durations when the maintenance window is uncertain.

## Validation and rollback

Tests, fixtures and emulator injection tools belong on a separate validation
branch. Record its commit alongside the production commit in the PR. A passing
Android build does not establish iOS simulator/device behavior.

To stop a message, publish `cancel`; no app rollback is required. If the feature
itself needs rollback, revert the app integration while retaining directory
preservation in publishing workflows. Never restore the old branch force-push
steps merely to disable notices.
