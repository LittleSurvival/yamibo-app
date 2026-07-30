## Context

`YamiboBackupFile` schema 1 already contains favorites, exportable settings, notes, bookmarks, and `BackupReadingState`. `BackupRepositoryImpl` snapshots and restores `ReadingHistory`, `ImageReadingHistory`, `MangaTagReadingHistory`, and `ReadingTimeStat`; those datasets are not missing and must remain covered by compatibility tests.

The concrete coverage drift is:

| Data | Current local backup | Ownership decision |
| --- | --- | --- |
| `TagCatalogReadingHistory` | Missing | Durable user reading position; include |
| `RssSearchReadingHistory` | Missing | Durable user reading position; include |
| `RssCatalogReadingHistory` | Missing | Durable user reading position; include |
| `LocalChapterState` | Missing | Durable read/progress state; include |
| `FavoriteUpdateEvent` | Missing | Durable user-visible event, canonical stable identity, read, and dismissed state; include |
| `FavoriteUpdateFidFilter.enabled` | Missing | User preference; include |
| `FavoriteUpdateCategoryFilter.enabled` | Missing | User preference; include through restored category identity |
| Filter labels/counts/timestamps | Missing | Derived from current favorites; rebuild |
| `FavoriteUpdateTrackedTarget` | Missing | Scanner baseline/fingerprint cache; exclude |
| `FavoriteUpdateRun` | Missing | Runtime progress/log/recovery state; exclude |
| Scheduler state and transient errors | Missing | Device/runtime state; exclude |

This change touches serialized models, SQL restore boundaries, UI reporting, and the canonical scope manifest. `YamiboBackupFile` is also used as an AppSync checkpoint boundary, so manifest selection, rather than model field presence, must decide whether FavoriteUpdate records enter a checkpoint.

## Goals / Non-Goals

**Goals:**

- Preserve and test all existing backup coverage.
- Add every currently missing durable reading-history/progress dataset listed above.
- Restore favorite-update events and the user-owned portion of update filters.
- Keep old `.yamibobak` files readable and make absent new sections decode as empty.
- Validate completely before destructive work and apply SQL-owned restore data in one transaction.
- Make backup scope and restore counts understandable in the existing UI.

**Non-Goals:**

- Do not change automatic cloud synchronization, AppSync domain registration, conflict policies, journals, or checkpoints.
- Do not back up scanner baselines, run logs, scheduling state, cache fields, remote sync metadata, authentication, cookies, formhash, download files, or platform paths.
- Do not redesign the existing manual Merge/Overwrite choice.
- Do not claim that reading history was previously absent.

## Decisions

### Audit the authoritative scope instead of duplicating ad hoc lists

Extend `PortableDomainManifest` (or its current equivalent) so every portable SQL/settings domain declares whether it is Local Backup, AppSync, both, or excluded with a reason. A contract test must fail when a new relevant SQL table is not declared or when an included domain lacks export and restore coverage.

This reuses the canonical-snapshot direction established by `unify-backup-appsync-snapshot` and prevents another silent drift between models, queries, clearing, summaries, and tests. A one-off set of new mappings without an inventory test was rejected because it recreates the current maintenance problem.

### Keep the wire extension additive under schema 1

Add default-empty sections to `YamiboBackupFile`/`BackupReadingState` and keep JSON `ignoreUnknownKeys = true` plus encoded defaults. Existing schema-1 fixtures decode with empty new sections; older app versions can decode a newer file and ignore fields they do not know instead of rejecting the whole file.

The implementation must not loosen the existing rejection of a schema version newer than `CURRENT_BACKUP_SCHEMA_VERSION`. A schema bump is deliberately deferred because `YamiboBackupFile` is also embedded in AppSync checkpoints; changing that version would create a coupled cloud migration unrelated to this local feature. A future incompatible field change must use a new schema version and an explicit migrator.

### Select Local Backup and AppSync checkpoint data through manifest scope

The snapshot reader must take an explicit manifest scope. Local backup selects all local-backup domains in this design. AppSync checkpoint creation selects only registered AppSync domains, so it excludes FavoriteUpdate events and choices until `sync-favorite-update-records` registers them. After registration, AppSync includes the same shared FavoriteUpdate projection exactly once; it must not introduce a second checkpoint section, transport model, or parallel mapper.

This avoids accidental cloud behavior changes before registration and prevents duplicated projection/transport behavior afterward.

### Model only durable favorite-update state

Back up every `FavoriteUpdateEvent` business field except the database autoincrement `id`: canonical `syncId` and `sourceFingerprint` when available, target identity, forum/display metadata, latest post title, mode, summary, detail ids, cover, detection time, read time, dismissed time, and ambiguous flag.

Reuse the one canonical identity function owned with `sync-favorite-update-records`; backup must not define a second identity contract. For new and normal events, `sourceFingerprint` is derived from canonical target, mode, and sorted/distinct immutable upstream post/thread/detail evidence, independent of local id, display title, summary, or device timestamp. `syncId` is derived from that canonical fingerprint. Only a legacy ambiguous event that lacks immutable evidence may use deterministic retained observation content plus its retained `detectedAt` as fallback, preserving rather than collapsing uncertain observations. Decode preserves valid stored `syncId`/`sourceFingerprint`; legacy rows or old backups missing them use the same shared backfill function.

Merge restore uses `syncId` to avoid duplicate logical events and combines `readAt`/`dismissedAt` by retaining the later non-null timestamp. Overwrite replaces the included event scope after validation.

Fid filter backup stores only stable `fid` and `enabled`. Category filter backup uses category `syncId`, never raw database `categoryId`; restore resolves it through restored/current favorites. Labels, item counts, and `updatedAt` are recomputed because they are derived.

An unresolved category filter is auxiliary preference state, not a structural failure of an otherwise valid backup. Restore skips that filter, increments an explicit skipped-orphan count, and reports a warning. It never applies the raw source `categoryId`. Broken required favorite graph relationships remain validation failures.

### Preserve current history merge identities and timestamp policy

The three existing history families and reading-time stats keep their current identities and restore behavior. New families use their SQL primary identity:

- tag catalog: `tagId`
- RSS subscription: normalized query plus optional forum scope; local autoincrement ids are not portable
- RSS search/catalog history: source `subscriptionId`, remapped through the restored subscription stable identity when the subscription is present in the backup
- chapter state: `(targetType, parentId, targetId)`

For Merge, if both sides contain the same identity, retain the row with the later user-progress timestamp (`lastVisitTime` or `updatedAt`). This avoids an older imported position replacing a newer local position. Overwrite replaces the included scope only after all validation succeeds.

### Validate first, then mutate atomically

Restore is phased:

1. Read the complete file.
2. Decode and reject unsupported schemas.
3. Validate enum/storage values, numeric bounds, duplicate identities, required favorite relationships, and configured collection-size limits.
4. Build the complete restore plan without writes, resolving category filters by category `syncId` and collecting unresolved auxiliary filters as skipped warnings.
5. Execute SQL clear/merge/apply for favorites, notes, bookmarks, all reading datasets, update events, and filter preferences in one `db.transaction`.
6. Apply preference-backed settings only through a rollback-capable staging mechanism, or take and restore a pre-apply settings snapshot if the SQL transaction or settings apply fails.
7. Rebuild derived update-filter rows after commit.

Overwrite must never call `clearRestorableData` before steps 1-4 finish. Any exception before commit leaves SQL and settings unchanged. Any exception during SQL apply rolls back every SQL-owned domain, including tables cleared for Overwrite.

### Report scope without adding another dense settings screen

Retain the existing backup page and controls. Change its title/subtitle or nearby concise scope text so it no longer implies only settings and favorites. Restore success reports separate counts for favorites, settings, reading/history records, and favorite-update events; notes/bookmarks may remain grouped according to the current compact UI.

No new restore mode or detailed per-record preview is introduced by this change.

## Risks / Trade-offs

- [Additive schema-1 fields make feature support implicit] -> Keep explicit compatibility fixtures for old and expanded schema-1 files and document that incompatible changes still require a version bump.
- [Backup and cloud derive different identities for one event] -> Reuse one canonical `sourceFingerprint`/`syncId` function in detectors, migration, backup adapters, cloud operations, and tests.
- [Legacy ambiguous events lack immutable evidence] -> Use retained observation content and `detectedAt` only for that fallback class, preserving separate observations rather than collapsing them.
- [Restoring category filters across remapped ids can target the wrong category] -> Resolve only through category `syncId`; skip unresolved auxiliary filters with warning/count and never apply the raw source database id.
- [Large event/history lists increase file and transaction size] -> Validate bounded collection sizes before mutation and test near-limit payloads; do not silently truncate user data.
- [SettingsStore is outside the SQL transaction] -> Stage or snapshot settings and test restoration after an injected post-clear/apply failure.
- [Shared snapshot code could omit or duplicate update records in cloud checkpoints] -> Require explicit manifest scope tests: excluded before cloud registration, then the shared projection included exactly once after registration.

## Migration Plan

1. Add coverage-manifest declarations and failing inventory tests for the four missing reading/progress datasets and favorite-update durable state.
2. Add default-empty boundary/canonical models carrying `syncId`/`sourceFingerprint` and old/new schema-1 fixtures.
3. Add complete SQL read/upsert/delete queries where absent.
4. Implement scoped export and preflight validation.
5. Implement transactional Merge/Overwrite restore plus settings rollback protection.
6. Update restore summaries and concise UI copy.
7. Run repository transaction/fault-injection tests, compatibility fixtures, UI-state tests, Android unit suites, iOS compilation, and strict OpenSpec validation.

Rollback consists of reverting the writer/restore implementation. Expanded schema-1 files remain readable by the prior app because unknown fields are ignored; the prior app simply does not restore the added sections.

## Open Questions

No blocking product decision is required for the apply phase. The following assumptions are explicit and should be changed in the spec before implementation if product intent differs:

1. "更新紀錄" means durable `FavoriteUpdateEvent` records plus user-selected filter enablement, not scanner baselines or historical run logs.
2. `LocalChapterState` is user-owned reading progress and belongs with "歷史紀錄" even though its table name is not a history table.
3. Merge must protect the later local/imported progress timestamp; Overwrite remains an explicitly destructive whole-scope restore.
4. Existing compact confirmation UI is retained; this change only corrects scope wording and result counts.
5. `sync-favorite-update-records` owns the shared canonical event identity and AppSync domain registration; this change consumes those contracts rather than redefining them.
