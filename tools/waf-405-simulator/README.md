# Android HTTP 405 WAF simulator

This debug-only harness exercises the production `YamiboClient` WAF path without waiting for
Baidu WAF to intercept the current network route.

## Run

Start the Android emulator, then run:

```properties
# local.properties
debugWafEnvironment=true
```

```powershell
.\gradlew.bat :composeApp:runDebugWafEnvironment
```

The task starts the simulator in the background and returns after launching the app. Stop it with:

```powershell
.\gradlew.bat :composeApp:stopDebugWafEnvironment
```

## Android Studio / IntelliJ Debug

After setting `debugWafEnvironment=true`, select `composeApp WAF Debug` from the IDE run
configuration list and use the normal Debug action. Its before-launch task starts only the WAF
simulator and emulator proxy; Android Studio still builds, installs, launches, and attaches the
debugger to the app, so App Inspection and Network Inspector can select the running process.

When finished, run the `Stop WAF Debug` configuration to stop the simulator and restore normal
emulator networking. Stopping the Android debugger alone does not clear the emulator-wide proxy.

Gradle generates a local CA before resource processing and embeds only its public certificate in
the flagged debug APK. No emulator-wide CA installation or rooted AVD is required. Logs stay under
`build/qa/waf-405-simulator`; CA private state and isolated Python packages stay under
`%LOCALAPPDATA%/Yamibo/waf-405-simulator`. Neither location is committed.

The proxy setting is emulator-wide while this task is active, so unrelated emulator traffic may
fail TLS validation. The stop task always restores the emulator's normal proxy setting.

## Behavior

- `debugWafEnvironment` is read only from `local.properties` and defaults to `false`.
- Normal debug builds trust system certificates only and do not start or configure a proxy.
- When enabled, the debug APK trusts only the generated simulator CA in addition to system CAs.
- Requests to `https://bbs.yamibo.com` without the simulator NOX cookie receive an evidence-shaped
  HTTP 405 response containing `__noxExpire`, `/nox_`, and `gangplank_` markers.
- The API-owned hidden WebView executes inline JavaScript and stores a test `nox_jst_v1` cookie.
- The hidden API-owned WebView reports that cookie to `YamiboClient`, allowing the original API
  request to be verified and replayed.
- The app's visible WebView uses its production user agent and bypasses the simulated Baidu edge,
  preserving the real CF-backed login and sign-in behavior. The API-owned hidden WebView inherits
  the API request user agent, so it still receives and solves the simulated challenge.
- Requests carrying that test cookie are forwarded to the real site after the proxy removes only
  the synthetic cookie. Authentication cookies are not logged or modified.
- Release and `releaseRun` variants never use the debug network-security configuration.
