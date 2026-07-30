## Why

The durable records shown in "最近更新" currently exist only in a device-local SQLDelight table, so changing devices or restoring local data loses detected updates and their read or dismissed state even when favorites are synchronized. The operation-log cloud sync registry must cover this user-relevant state without uploading transient scan execution data or rebuildable caches.

## What Changes

- Add durable FavoriteUpdate event records to the operation-log cloud sync domain registry, mutation outbox, reducer, checkpoint projection, bootstrap, materializer, and force-override comparison.
- Give each FavoriteUpdate event an immutable sync identity independent of its local autoincrement id.
- Synchronize explicit event read and dismiss mutations with deterministic conflict handling and tombstone retention.
- Synchronize the user's enabled/disabled forum and favorite-category update filters while keeping display names and item counts locally derived.
- Resolve category filter identity through `LocalFavoriteCategory.syncId`, never the device-local category id.
- Report normal-sync and force-override changes by module, action, count, and bounded human-readable item details so users can identify which recent-update records or filters changed.
- Keep FavoriteUpdate run state, progress, logs, warnings, errors, tracked-target baselines, check metadata, and other rebuildable scan caches local.
- Add migrations and focused domain, repository, checkpoint, bootstrap, materialization, force-preview, detailed-summary, and cross-device rollout tests.

## Capabilities

### New Capabilities

- `favorite-update-cloud-sync`: Defines stable identities, durable scope, operation schemas, conflict policies, tombstones, checkpoint/bootstrap behavior, materialization, summaries, migrations, and safety boundaries for synchronizing FavoriteUpdate events and filter choices.

### Modified Capabilities

None.

## Impact

- Affected shared code includes `FavoriteUpdateRepository`, its SQLDelight implementation and tables, `BackupModels`, the AppSync domain registry, mutation recorder integration, reducer/materializer, checkpoint/bootstrap adapters, and operation change summaries.
- Database migrations must backfill stable event identities and category sync identities without treating absent local rows as deletion authority.
- Cloud payload size grows with retained update events; this change preserves records and tombstones and reports storage pressure rather than inventing an automatic retention policy.
- Existing clients that do not understand the FavoriteUpdate domains must fail closed under the operation-log envelope compatibility policy rather than silently drop or rewrite those operations.
- No Yamibo API request contract or cloud blog transport behavior changes.
