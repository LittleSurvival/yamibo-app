# Emulator Validation

Validated on 2026-07-30 with the debug application id
`me.thenano.yamibo.yamibo_app.debug`. APK updates used `adb install -r -t -d`;
the app package was never uninstalled and `pm clear` was never used.

## Single-device data preservation

- Pixel 8 Pro DB inode remained `362103` across repeated IDE-equivalent overlay
  installs, force-stops, cold starts, and cloud-page navigation.
- Before the intentional reset probe, process restart preserved
  `ACTIVE|nextSequence=46|45 acknowledged|2 cached blogs` byte-for-byte at the
  queried state boundary.
- No fatal app exception was observed.

## Verified publication

- First pull-only bootstrap and migration publication converged in 6,493 ms:
  43/43 local migration operations were authoritatively reloaded and marked
  `ACKNOWLEDGED`; pending and quarantine counts were zero.
- A real light/system theme round trip produced two durable local operations.
  The next manual run converged in 6,632 ms and authoritatively verified the
  journal plus advisory index.
- Later mutation runs continued to leave every outbox row acknowledged and no
  pending duplicate.

## Database-reset bootstrap

- The original DB was preserved as an app-internal probe fixture and removed
  from the active database path. The recreated DB received a new inode
  (`362130`) and initialized as `UNBOUND|nextSequence=1|0 outbox|0 blogs`.
- Manual sync applied 45 cloud operations before creating the reset
  installation's empty journal. It produced `ACTIVE|0 outbox|45 applied|43
  resolved entities|3 cached blogs`.
- A bidirectional SQL `EXCEPT` between pre-reset and post-bootstrap
  `AppSyncResolvedEntity` rows returned zero differences.
- The bootstrap regression discovered during this run was fixed: a captured
  local snapshot can now create migration operations only for stable entity
  keys absent from verified cloud state. A reset/default local value cannot
  overwrite an existing cloud key.

## Foreground/background overlap

- WorkManager periodic work was made due in its test DB, then forced while a
  foreground manual sync held the SQL lease.
- The foreground owner acknowledged both pending operations. The background
  loser returned `RETRYPENDING/RETRY` in 24 ms, and WorkManager subsequently
  reran it to `ACTIVE/CONVERGED` in 2,741 ms.
- The first probe exposed an incorrect `MANUAL_INTERVENTION` telemetry
  classification for lease contention. The service now classifies this path as
  retry, with a focused contract test.
- No duplicate operation, database lock, or second publisher was observed.

## Cloud-load failure

- Airplane mode made Yamibo DNS unavailable before a manual run.
- Before and after the failed load, the data boundary was identical:
  `ACTIVE|43 resolved|8 acknowledged|45 applied|INDEX+2 JOURNAL cache rows`.
- The only new state was a `RETRYPENDING/RETRY` reliability run. No local
  domain data, acknowledgement, cached identity, or remote content changed.
- After network restoration, the next manual run converged in 5,887 ms.

## Reliability evidence boundary

- The deterministic engine suite executes 100 eligible demands. Ten demands
  receive an accepted POST followed by an unknown response and therefore need a
  second execution window. All 100 reach a fixed point, all 100 operation ids
  remain unique, and all 100 operations finish authoritatively acknowledged.
- A completion audit found that runtime telemetry originally persisted each
  retry attempt as a separate denominator row. The service now reuses the same
  reliability `runId` and original start time until that eligible demand
  converges or reaches a terminal outcome. Retry count still increases, but a
  transient attempt no longer lowers the demand-level convergence rate.
- A live airplane-mode probe verified the corrected persistence contract. One
  demand first ended `RETRYPENDING/RETRY`, then network recovery changed that
  same row to `ACTIVE/CONVERGED` with `retryCount=1`; its original start time
  remained unchanged, total duration was 58,981 ms, and SQL found exactly one
  row for the demand id.
- This is pre-rollout engineering evidence, not a provider-wide production SLA.
  The feature flag remains disabled by default. Routine snapshot reconciliation
  cannot be removed until real rolling rollout telemetry also meets the target.

## Two-device convergence

The same final debug APK was installed with `adb install -r -t -d` on an
authenticated Pixel 8 (`emulator-5554`) and Pixel Tablet (`emulator-5556`).
Neither package was cleared or uninstalled.

- Before enabling the test feature flag, the tablet was
  `UNBOUND|nextSequence=1|0 outbox|0 resolved`. Enabling the flag created two
  canonical local setting projections but still created no outbox operation.
- Pull-only bootstrap applied 53 cloud operations, produced 43 resolved setting
  entities, retained `nextSequence=1`, and created its own journal only after
  cloud adoption. No default/local snapshot operation was published.
- All 43 semantic `encodedState` values and generations matched the Pixel 8.
  The only initial row difference was the intentionally local projection
  timestamp.
- A tablet-originated `thememode=DARK` operation became authoritatively
  acknowledged, was received on the Pixel 8 by exact operation id, and restored
  43/43 semantic equality.
- A Pixel-8-originated `themescheme=CATPPUCCIN` operation was likewise received
  on the tablet by exact operation id, proving both transport directions.
- Both devices then created same-field edits from causal contexts that did not
  include the peer's new operation: Pixel 8 wrote `LIGHT`; Pixel Tablet wrote
  `SYSTEM`. Concurrent syncs wrote separate journals, both devices received the
  peer operation, deterministically selected `LIGHT`, and retained `SYSTEM` as
  loser history without a user conflict choice.
- Force-stop/cold-start preserved both database inodes (`362130` and `361653`).
  Another concurrent fixed-point run left 43/43 semantic states equal, three
  journals cached on each device, and both latest demands `CONVERGED` with zero
  pending and zero quarantined operations.
- Test appearance changes were restored through normal UI operations to the
  original `thememode=SYSTEM` and `themescheme=DEFAULT`. Both restore operations
  were acknowledged and received by the tablet; final semantic comparison
  remained 43/43 equal with zero pending and zero quarantine on both devices.

## Manual authority override UI

- The final debug APK was overlaid on both authenticated emulators with
  `adb install -r -t`; database inodes remained `362130` on Pixel 8 and
  `361653` on Pixel Tablet.
- The Pixel 8 cloud page displayed the verified epoch as
  `2026/07/29 04:31`, not as raw epoch milliseconds.
- A force-pull preview loaded the verified cloud state without mutating either
  side and showed a semantic comparison (`收藏分類 / 新增 1`) before
  confirmation.
- The confirmation action was disabled during the countdown (`5 秒後可確認`)
  and became enabled only after the full 10-second wait.
- The dialog was cancelled; no force push, force pull, cloud deletion, or local
  replacement was executed during visual validation. The Pixel 8 database inode
  remained `362130`.
- The page was cold-started under Android night mode and visually checked with
  the Yamibo theme. The formatted date, detail labels, action buttons, warning
  text, and destructive red action remained legible without text/background
  contrast regressions.
