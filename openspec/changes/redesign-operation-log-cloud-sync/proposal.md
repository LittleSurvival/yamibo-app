## Why

The current whole-snapshot upload/download design cannot safely distinguish an empty or stale installation from an intentional deletion, and Yamibo blog edits provide no compare-and-swap protection against two devices overwriting each other. Cloud sync must be rebuilt around durable operations and deterministic conflict policies so eligible sync sessions converge automatically at least 99% of the time without risking acknowledged user data.

## What Changes

- **BREAKING** Replace routine whole-snapshot Merge/Overwrite reconciliation with a local-first operation log and transactional outbox.
- Store each installation's operations in its own private Yamibo device-journal blog so no two devices normally write the same cloud document.
- Retain a config/index blog only for account binding and discovery acceleration; stale or missing index entries are never evidence that data was deleted.
- Identify operations with a stable device id and monotonic sequence, carry causal context, deduplicate retries, and use wall-clock timestamps only for diagnostics.
- Resolve settings, records, relationships, progress, and deletions with explicit domain policies; true concurrent same-field edits choose a deterministic winner while retaining the losing operation in history.
- Treat fresh, reset, corrupted, or over-90-day-inactive installations as pull-only bootstrap clients until they have adopted a verified cloud checkpoint.
- Represent deletion only through explicit tombstone operations. Empty local state, failed loads, logout, migration, or database recreation cannot generate remote deletion.
- Quarantine suspicious bulk deletion and unsupported or invalid operations without blocking unrelated valid operations.
- Add verified journal writes, bounded retry/rebase, checkpointed compaction, recovery history, and measurable reliability telemetry.
- Define the 99% target over eligible sessions and separately require zero known loss of acknowledged operations.
- Preserve the existing typed Yamibo blog CRUD/discovery transport and `BackupModels` payload mapping, but do not claim confidentiality: journal encoding is JSON plus compression/base64, not encryption.
- Keep manual restore as an explicit recovery workflow; it is not part of normal automatic conflict resolution.

## Capabilities

### New Capabilities

- `operation-log-cloud-sync`: Defines local transactional outbox storage, per-device Yamibo journals, discovery, pull/apply/publish flow, idempotency, verification, and checkpoint compaction.
- `cloud-sync-conflict-resolution`: Defines causal comparison and deterministic per-domain conflict policies, tombstones, retained loser history, and invalid-operation quarantine.
- `cloud-sync-safety-reliability`: Defines bootstrap state, 90-day device inactivity, destructive-operation safeguards, failure invariants, retry behavior, observability, and the measurable 99% automatic-sync objective.

### Modified Capabilities

None. These capabilities supersede the unarchived `redesign-app-sync-architecture` and `add-automatic-cloud-sync` snapshot reconciliation strategy rather than modifying an accepted main spec.

## Impact

- Affects `shared/src/commonMain/kotlin/me/thenano/yamibo/yamibo_app/repository/appsync/**`, sync-domain mutation entry points, SQLDelight schema/migrations, background scheduling, and cloud-sync UI state.
- Reuses the existing `BackupModels` adapters, typed blog identifiers, config-blog store, Yamibo API request handling, typed success/error response parsing, and authoritative initial pulls.
- Adds device identity/epoch state, outbox and operation-history tables, causal high-watermarks, tombstones, journal metadata, checkpoint acknowledgements, quarantine records, and sync-run telemetry.
- Requires all syncable local mutations to participate in the same database transaction as their outbox operation.
- Requires deterministic multi-device model tests, process/network failure injection, stale/reset-device tests, blog race tests, compaction recovery tests, and emulator verification.
- Does not require a new backend, actual payload encryption, manual conflict UI, or remote deletion caused by failed cloud loading.
