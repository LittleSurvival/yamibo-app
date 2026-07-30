## Why

The operation-log engine cannot safely replace the abandoned routine snapshot Merge/Overwrite path based only on deterministic unit tests. Removal requires reproducible real-device evidence that eligible sync demands converge at least 99% of the time with zero acknowledged-operation loss, including the newly registered favorite-update domains.

## What Changes

- Define a rollout evidence protocol with an explicit eligible-demand denominator, exclusions, fixed-point success predicate, and zero-loss durability invariant.
- Exercise real local favorites, reading/history, update scans, settings, lifecycle changes, additions, and removals on data-preserving emulator installs.
- Verify foreground, background, restart, duplicate delivery, two-device convergence, force recovery, failed cloud load, and stale/default-device safety.
- Record privacy-safe evidence directly in the OpenSpec without user content, authentication material, raw account identifiers, or cloud payloads.
- **BREAKING** Remove the abandoned routine cloud snapshot Merge/Overwrite decision implementation only after the evidence gate passes; retain manual `BackupModels` export/restore and rollback readability.

## Capabilities

### New Capabilities

- `app-sync-rollout-verification`: Defines the measurable rollout gate, real-device scenario matrix, evidence format, and retirement/rollback rules for routine snapshot synchronization.

### Modified Capabilities

None.

## Impact

- AppSync rollout telemetry and test evidence.
- Android emulator fixtures, WorkManager execution, SQLDelight state inspection, and Yamibo blog transport.
- Removal of unused routine cloud snapshot merge/overwrite decision code after the gate passes.
- No removal of local manual backup Merge/Overwrite recovery behavior.
