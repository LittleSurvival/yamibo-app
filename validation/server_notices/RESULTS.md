# Server notice validation — 2026-10-22

Production: `23e2a6e338546aa91be48972c6ed591cc7d6e493` (PR #61).
Unit/native validation: `8dac3bba29b91aa113f6c84a8a60ab336769c265`, branch
`stable-v0.0.9-server-notice-31a82bd-a01dfc4-validation`.
The production Kotlin source trees match that validation commit exactly; only
test sources/dependencies, validation scripts and validation CI are additional.

## Automated results

- `./gradlew.bat :shared:testDebugUnitTest :composeApp:compileDebugKotlin --console=plain`: passed; 18 Kotlin tests, zero failures/errors/skips.
- `python validation/server_notices/test_publishing.py`: passed; 5 tests, including workflow YAML structure, shell syntax, input validation, real local Git pushes preserving unrelated directories, revision rollback rejection and the actual embedded stop-update script.
- Production `:composeApp:assembleDebug`: passed with standard debug signing. The original local signing settings were restored afterwards.
- [macOS native compile](https://github.com/LittleSurvival/yamibo-app/actions/runs/39757929048): shared iOS code and Compose iOS integration both passed. This is compilation, not simulator UI/device execution or a Swift host build.
- Production test-isolation, whitespace and empty OpenSpec-diff guards passed; PR test-isolation CI passed.

## Android emulator evidence

Pixel_9, Android 17, `emulator-5554`; isolated package
`me.thenano.yamibo.yamibo_app.noticeqa.debug`. The normal installed app's data
was not cleared or replaced.

The QA branch adapts an actual loopback HTTP response to yamibo-api's error
envelope in `ServerNoticeQaHttp`, replaces only home-page requests and the three
notice URLs, and enables cleartext only for `10.0.2.2` in its debug network policy.
This tests real notice HTTP fetches and production repository/resolver/UI code;
it does not reproduce a real Cloudflare origin outage or SDK WAF processing.

| Scenario | Result | Screenshot |
| --- | --- | --- |
| Active notice + HTTP 524 | Home error card displays the exact operator message | [active](evidence/server-notice-active.png) |
| Operation feedback | Production feedback host displays the notice | [feedback](evidence/server-notice-feedback.png) |
| Background notification presentation | Production Android favorite notification gateway displays the notice and app icon | [notification](evidence/server-notice-notification.png) |
| Expiry while response remains cached | Notice first displayed; after its 20-second fixture validity ended, the retry button returned the original 524 without waiting for the sixty-second refresh throttle | [expired](evidence/server-notice-expired.png) |
| Partial mirror cancellation | A newer disabled revision on source 3 overrides two older active sources | [cancelled](evidence/server-notice-cancelled.png) |
| Other HTTP status | HTTP 503 remains unchanged even with an active notice feed | [other status](evidence/server-notice-other-status.png) |

The notification is injected through the real notification gateway; WorkManager
scheduling, favorite database population and a full unattended synchronization
run are not established by that screenshot. UI trees accompany every capture.

## Reproduction

Use the unit-validation branch for production-source tests; this emulator branch
intentionally contains QA-only source modifications. Install Python requirements
from `validation/server_notices/requirements.txt` for the publishing tests.

For emulator QA, run `python -u validation/server_notices/fixture_server.py`, with
`build/server-notice-fixture.json` containing:

```json
{
  "mode": "active",
  "revision": 100,
  "startsAt": 1792660000000,
  "expiresAt": 1792663600000,
  "forumStatus": 524
}
```

Replace the timestamps with the current Unix milliseconds and a future expiry;
increase the revision between scenarios. `cancelled` makes source 3 publish a
newer disabled revision, and `unavailable` makes all notice sources return 503.
`forumStatus` independently controls the home request's actual HTTP response.
Restart the fixture server after changing its Python source, but ordinary control
JSON changes are read on every request.

Build/install the QA debug APK with standard debug signing and grant notification
permission. Launch MainActivity normally for the error page, or with boolean
extras `qa_feedback=true` / `qa_notification=true` to exercise presentation after
a four-second delay. Wait for the expected text in the UI tree before capturing;
derive retry coordinates from that tree rather than from screenshots.

The reused emulator had insufficient storage for package installation. Its two
storage-reserve settings were temporarily lowered and restored to their prior
unset values after validation. No existing app data was deleted. The fixture
server and isolated QA install are temporary validation resources.

## Remaining limits

No real maintenance announcement was published to the three public feeds. The
manual workflow is available after merging it to the default branch. Mirror
credentials/publication reachability still require the operator's real run.
iOS simulator/device runtime behavior and native background scheduling require
separate platform acceptance; no such runtime pass is claimed here.
