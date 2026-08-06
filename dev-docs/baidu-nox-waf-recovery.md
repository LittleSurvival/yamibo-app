# Baidu NOX WAF recovery integration

`yamibo-api` owns WAF detection, NOX cookie isolation, safe probing, replay policy, single-flight coordination, and the Android/iOS system WebView implementations. `yamibo-app` owns only the foreground lifecycle signal and mounts one API composable behind its existing navigation content.

## Runtime ownership

- Android and iOS foreground repositories share one `YamiboClient`; the client owns WAF state, cookies, coordination, and lifecycle.
- `YamiboWafRecoveryRoot` places `YamiboWafChallengeHost` before the visible app content, preserving navigation, scroll state, and touch handling without any WAF overlay, message, control, or visible browser.
- WorkManager, reminder, update, debug-probe, and iOS background entry points intentionally use headless `YamiboClient` instances. They must treat `YamiboResult.WafChallenge` as deferred foreground work; they must not open UI or report logout.
- The existing sign-in WebView remains independent. WAF recovery does not replace sign-in.
- Logout calls `YamiboClient.clearCookies()`, then clears the app stores. Ordinary WAF failure never clears Discuz login state.

## Security and replay rules

- Only an HTTP 405 body containing an observed NOX marker (`__noxExpire`, `/nox_`, or `gangplank_`) starts recovery; diagnostic headers alone and ordinary HTTP 405 responses remain ordinary errors.
- The WebView permits only HTTPS Yamibo same-origin top-level navigation, exposes no JavaScript bridge, and disables file/content access where supported.
- Recovery stays silent. A normal-viewport WebView runs behind app content, polls the platform cookie store without waiting for page completion, and is disposed after success or typed failure.
- `nox_jst_v1` is stored separately from authentication cookies and is never included in diagnostics.
- Safe reads may replay once. Every write declares `SAFE_ONCE`, `AFTER_CONFIRMED_EDGE_REJECTION`, or `NEVER`; no write has an implicit default.
- Recovery verifies the acquired cookie with a safe GET before replay and prevents recovery/replay loops.

## Rollback

Construct the foreground client with `WafRecoveryConfig(enabled = false)` to retain typed WAF detection while disabling WebView recovery. The App host may then remain mounted safely or be removed in a follow-up release.

## Remaining release gates

Physical/simulator WebView lifecycle tests, Guangzhou route validation, controlled write duplicate detection, iOS archive size comparison, and Maven Central publication remain release gates. Live authentication and NOX cookie values must never be saved in fixtures, logs, screenshots, or reports.
