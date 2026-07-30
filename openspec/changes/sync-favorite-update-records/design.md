## Context

`FavoriteUpdateRepositoryImpl` currently stores four kinds of state:

- `FavoriteUpdateEvent`: user-visible detected updates with `readAt` and `dismissedAt`.
- `FavoriteUpdateFidFilter` and `FavoriteUpdateCategoryFilter`: user choices mixed with derived names and item counts.
- `FavoriteUpdateRun`: execution progress, log, warning, and error state.
- `FavoriteUpdateTrackedTarget`: rebuildable detection baselines, fingerprints, check timestamps, and retry metadata.

The operation-log sync registry and `DatabaseSyncDomainMaterializer` do not cover any of these tables. `FavoriteUpdateEvent.id` and category filter `categoryId` are local database ids and cannot be cloud identities. Existing checkpoints serialize `YamiboBackupFile` plus resolved operation entities; bootstrap captures a local backup projection, adopts verified cloud state first, and only then emits non-conflicting migration operations.

This design extends those existing operation-log contracts. It does not create a second synchronization path.

## Goals / Non-Goals

**Goals:**

- Converge FavoriteUpdate events, read state, dismiss state, and filter choices across devices without wall-clock winner selection.
- Deduplicate the same upstream update detected independently by multiple devices.
- Preserve explicit deletions with tombstones and never infer deletion from missing rows, refresh cleanup, reset databases, or failed cloud loads.
- Keep checkpoint, bootstrap, force push/pull, and user-facing change summaries complete for the new domains.
- Migrate existing local data without allowing a reset/default installation to overwrite verified cloud state.

**Non-Goals:**

- Synchronizing update scan progress, run status, logs, warnings, errors, tracked-target baselines, provider fingerprints, last-check metadata, or retry counters.
- Synchronizing locally derived filter labels or item counts.
- Changing Yamibo blog transport or request handling.
- Adding automatic event-retention or cloud-purge policy. Existing provider storage-pressure behavior remains authoritative.
- Adding a user-facing unread/dismiss undo command that does not currently exist.

## Decisions

### 1. Register three explicit domains

Add these contracts to `SyncDomainRegistry.REQUIRED_DOMAIN_IDS` and `Default`:

| Domain | Entity identity | Operations | Policy |
| --- | --- | --- | --- |
| `favorite.update-event` | `event:<stable source fingerprint>` | `Put`, `Patch`, `Delete` | per-field register with remove-wins tombstone |
| `favorite.update-fid-filter` | `fid:<decimal fid>` | `Put`, `Patch` | per-field register |
| `favorite.update-category-filter` | `category:<category syncId>` | `Put`, `Patch` | per-field register |

All domains use generation 1. An event deleted with a tombstone is never recreated for the same source fingerprint. A genuinely different upstream update receives a different identity.

Alternative: store all FavoriteUpdate state in one settings-style blob. Rejected because it would reintroduce whole-snapshot overwrite, make independent events conflict, and prevent useful force-preview summaries.

### 2. Derive event identity from immutable source evidence

Add a unique non-null `syncId` and `sourceFingerprint` to `FavoriteUpdateEvent`. New detectors canonicalize:

```text
targetType | targetId | authorId-or-0 | mode | source discriminator
```

The source discriminator is the sorted distinct immutable post/thread ids for normal, novel, RSS, and non-ambiguous tag events. An ambiguous event must persist a deterministic observation fingerprint from the scan evidence that caused it; the tracked-target cache itself remains local. `syncId` is `event:` plus the shared stable fingerprint of this canonical material.

Existing rows backfill from their canonical target/mode/detail ids. Legacy ambiguous rows with no detail ids additionally include their existing `detectedAt` and content fingerprint so each row receives a stable identity without collapsing unrelated observations. The migration is deterministic for repeated execution on the same data, although independently created legacy ambiguous rows are not promised to deduplicate.

Alternative: use the local autoincrement id or a random UUID. Rejected because local ids collide and random ids duplicate the same upstream event when two devices scan independently.

### 3. Treat event lifecycle markers as monotonic facts

An event `Put` contains immutable/source and display fields:

`targetType`, `targetId`, `authorId`, `fid`, `forumName`, `title`, `latestPostTitle`, `mode`, `summary`, canonical `detailIds`, `coverUrl`, `detectedAt`, `ambiguous`, and `sourceFingerprint`.

`readAt` and `dismissedAt` are optional fields and are never written as null. User actions emit `Patch(readAt=<time>)` or `Patch(dismissedAt=<time>)`. Since the current product has no unread or undismiss action, once either field exists it cannot be cleared by a concurrent or older event `Put`. Concurrent non-null marker writes use the standard deterministic operation-id tie-break; timestamps are diagnostic values, not causal ordering.

Dismiss is not physical deletion: the materializer keeps the row and the existing active-event query hides it. Physical purge, if invoked by an explicit repository command, emits `Delete` tombstones atomically. Absence from a database, backup, scan result, or filter refresh creates no delete.

Alternative: model dismiss as a tombstone. Rejected because dismiss is recoverable historical user state and deleting the entity would discard its useful event content.

The domain's LWW behavior is logical, not wall-clock based:

1. An operation that causally observes another operation is later and wins for the fields it writes.
2. Concurrent writes use the existing deterministic operation-id ordering.
3. `detectedAt`, `readAt`, and `dismissedAt` remain user-visible event data and diagnostics; they never decide causal freshness.
4. A same-generation event tombstone is remove-wins against concurrent or replayed Put/Patch operations.
5. Recreation after an observed tombstone requires an explicit authorized new generation; routine scanning cannot resurrect it.

This ordering must be shared by normal pull, checkpoint rebuild, bootstrap, and force planning so different entry points cannot select different winners.

### 4. Separate durable filter choices from derived projections

Add durable choice storage:

- FID choice: `fid`, `enabled`, winning operation metadata.
- Category choice: `categorySyncId`, `enabled`, winning operation metadata.

The existing filter tables remain local projections containing names, item counts, and local category ids. Refresh merges derived rows with durable choices:

- Missing choice means enabled by default.
- Existing choice overrides the default.
- Removing a derived filter row does not remove or tombstone its durable choice.
- Category choices resolve `categorySyncId` to the current local category id during projection; unresolved choices remain durable and dormant until the category exists.

Only `setFidEnabled` and `setCategoryEnabled` emit operations. Automatic refresh never emits operations. Concurrent enable/disable changes follow causal ordering, then deterministic operation-id ordering.

Alternative: sync current filter rows. Rejected because names/counts are derived and category ids are device-local.

### 5. Record repository mutations atomically

Inject the existing `AppSyncMutationRecorder` boundary into `FavoriteUpdateRepositoryImpl`.

- Event detection computes the stable identity, upserts once, and appends its `Put` in the same transaction. Redetection of an existing identity is idempotent and does not create a duplicate logical event.
- Read and single dismiss resolve local id to `syncId`, append a `Patch`, and update the row atomically.
- Batch dismiss and dismiss-all use `recordBatch`/`recordCommand` so all selected event patches and local updates form one transaction.
- Filter toggle methods append their choice `Put` or `Patch` with the local durable choice update atomically.
- When AppSync cannot record because the installation is not yet bound, local behavior remains available and the bootstrap migration projection captures the durable rows later.

### 6. Extend backup/checkpoint projection without duplicating transport

Reuse the FavoriteUpdate payload added to `BackupModels` by the local "收藏與設定備份" completion change. The payload must contain event stable identity/source fields, lifecycle markers, FID choices, and category choices keyed by category `syncId`; it must not contain run or tracked-target state.

`BackupSnapshotMigrationPlanner` creates migration `Put` drafts for local event and filter entities. Bootstrap continues to load verified cloud state first and drops a local migration draft when the same domain/entity already exists remotely. A fresh or reset database therefore cannot publish absence/defaults over cloud records.

Checkpoints include both the updated backup projection and resolved entities/tombstones. Checkpoint validation and tests assert that the FavoriteUpdate projection agrees with the reduced entities. The cloud operation state remains authoritative; `BackupModels` is checkpoint/manual-recovery projection, not an incremental diff source.

Implementation ordering assumption: if the local-backup change lands first, this change consumes its models. If this change is applied first, it adds the same schema section once and the other change must rebase rather than introduce a duplicate model.

### 7. Materialize only durable FavoriteUpdate state

`DatabaseSyncDomainMaterializer` applies event and choice domains in the same SQLDelight transaction as operation markers and causal watermarks.

- Event `Put/Patch` upserts by `syncId` while preserving a stable local autoincrement id for UI APIs.
- Event tombstone deletes the matching event row.
- Filter entities update durable choice rows, then projection reconciliation refreshes applicable local filter rows.
- `clearSyncableData` clears FavoriteUpdate events and durable choice rows but does not clear `FavoriteUpdateRun` or `FavoriteUpdateTrackedTarget`.
- Failed apply, bootstrap, force pull, or checkpoint adoption rolls back event/choice changes and sync metadata together.

### 8. Make force operations and normal summaries explain the result

Semantic force comparison includes all three new domains. It compares event content/lifecycle and filter `enabled` values, not local ids, labels, item counts, or tracked-target state. The stale-preview token includes the new domain fingerprints.

Extend summary classification so the UI can report:

- Favorite updates: added, updated, read, dismissed, or deleted.
- Forum/category update filters: enabled or disabled.

Each summary entry contains a stable module id, localized module label, semantic action, total count, and a bounded list of display details. Event details use the event title plus target/forum context when available. Filter details use a locally resolved forum/category label and fall back to `FID <value>` or a shortened category sync identity when no label is available. Raw database ids and full operation ids are never displayed.

Normal-sync results, the expandable sync-details section, and force push/pull previews use the same summary model. The UI shows at most five item details per module/action group and an explicit remaining count, preserving a compact page while still explaining which content changed. A summary formatting failure falls back to the correct module/action/count and never fails or rolls back synchronization.

Force push creates later operations through the same registry and tombstone rules. Force pull replaces only cloud-syncable FavoriteUpdate events/choices and leaves local run/cache tables untouched. A failed authoritative cloud load changes neither durable nor transient local data.

## Risks / Trade-offs

- [Legacy ambiguous events may not deduplicate across independently migrated devices] -> Use deterministic source fingerprints for all new events and preserve each legacy ambiguous row rather than risk data loss.
- [Event history increases checkpoint and journal size] -> Reuse provider storage-pressure pause/reporting and do not silently prune user records; define retention only in a later product-policy change.
- [Filter choices can arrive before their category] -> Store choices by category `syncId` and materialize them when the category becomes resolvable.
- [Concurrent detector content may differ for one event identity] -> Validate immutable identity fields, resolve display fields deterministically, and retain conflict history.
- [Force pull can intentionally discard pending local event actions] -> Include new domains in the preview, revalidate its token after the 10-second confirmation gate, and use existing explicit force semantics.
- [Concurrent update scan and cloud materialization can race] -> Keep event mutation plus outbox atomic and execute materialization in database transactions; add interleaving tests.

## Migration Plan

1. Add schema columns/indexes for event identity and durable FID/category choice tables.
2. Deterministically backfill every existing event `syncId`/`sourceFingerprint`; copy current filter enabled values into durable choices, resolving category ids through `LocalFavoriteCategory.syncId` and skipping unresolved orphan projections.
3. Extend `BackupModels`, snapshot/restore adapters, and migration planner while preserving decoding defaults for old backups.
4. Register and test the three domains before any new operation can be emitted.
5. Wire mutation recording and transactional materialization.
6. Extend checkpoint/bootstrap and force-summary behavior.
7. Roll out with old clients failing closed on unsupported domains as already specified by the operation-log envelope policy.

Rollout acceptance requires:

- deterministic model convergence across two to five replicas under duplicate and reordered delivery;
- migration coverage for pre-sync events, lifecycle markers, disabled filters, ambiguous identities, and unresolved categories;
- data-preserving two-emulator validation for event creation, read, dismiss, FID/category toggle, restart, and background/foreground sync;
- evidence that empty/reset state and failed cloud loads emit no deletion/default-overwrite authority;
- UI evidence that normal and force flows identify changed modules/actions/items without exposing internal ids;
- zero acknowledged-operation loss or unexplained projection divergence in the validation run.

Rollback must not downgrade a database after the migration. Feature rollback disables emission/apply of the new domains only after preserving their operations for a compatible build; it must not delete event or choice rows.

## Open Questions

No implementation-blocking questions remain.

Assumptions:

- `readAt` and `dismissedAt` are one-way lifecycle markers because the current repository exposes no unread/undismiss operation.
- There is no automatic retention period for FavoriteUpdate events in this change.
- `FavoriteUpdateRun` and `FavoriteUpdateTrackedTarget` remain local even when a force pull replaces cloud-syncable FavoriteUpdate state.
