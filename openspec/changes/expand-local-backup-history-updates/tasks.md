## 1. Coverage Contract

- [x] 1.1 Extend the portable-domain manifest with `TagCatalogReadingHistory`, `RssSearchReadingHistory`, `RssCatalogReadingHistory`, `LocalChapterState`, favorite-update events, and update-filter preferences
- [x] 1.2 Record explicit exclusions for tracked targets, update runs, scheduler state, derived filter metadata, authentication, remote sync metadata, caches, downloads, and platform paths
- [x] 1.3 Add a contract test that fails for undeclared relevant SQL/settings domains and missing Local Backup export/restore adapters
- [x] 1.4 Add regression assertions that existing thread, image, tag-manga, and reading-time backup coverage remains complete
- [x] 1.5 Classify `RssSearchSubscription` as portable favorite state while keeping RSS result/page caches excluded

## 2. Backup Boundary And Compatibility

- [x] 2.1 Add default-empty backup/canonical models for tag-catalog, RSS-search, RSS-catalog, and chapter progress records with every persisted user-progress field
- [x] 2.2 Add backup models that preserve FavoriteUpdate `syncId`/`sourceFingerprint`, complete event/lifecycle fields, and stable fid/category filter enablement without database event ids or derived metadata
- [x] 2.3 Keep the additive extension on schema 1, preserve unknown-field tolerance, and add fixtures for legacy schema-1 files with omitted sections
- [x] 2.4 Add expanded schema-1 round-trip fixtures and unsupported-future-schema rejection tests
- [x] 2.5 Make snapshot creation select an explicit manifest scope and prove FavoriteUpdate is excluded before cloud registration, then the shared projection is included exactly once after registration
- [x] 2.6 Add stable RSS subscription backup models and remap RSS history from source local ids to restored subscription ids

## 3. SQL Access And Export

- [x] 3.1 Add complete `getAll`/upsert/delete queries required for the three newer history tables, chapter state, update events, and filter preferences
- [x] 3.2 Export all existing and new reading/history datasets through the scoped canonical snapshot reader
- [x] 3.3 Reuse the cloud-sync canonical fingerprint function to export/preserve `sourceFingerprint` and `syncId` from target, mode, and immutable upstream evidence
- [x] 3.4 Add legacy ambiguous fallback export/backfill tests using retained observation content and `detectedAt` only when immutable evidence and stored identity are absent
- [x] 3.5 Export fid enablement by fid and category enablement by category `syncId` while omitting local category ids, labels, counts, timestamps, baselines, runs, and logs
- [x] 3.6 Add export tests with representative records from every included domain and explicit exclusion assertions

## 4. Restore Preflight And Safety

- [x] 4.1 Build a write-free restore plan after complete read/decode/migration with enum, bounds, duplicate-identity, relationship, and collection-size validation
- [x] 4.2 Resolve required favorite graph relations before the transaction; classify unresolved category `syncId` filters as skipped auxiliary state with warning/count
- [x] 4.3 Move Overwrite clearing for every included SQL domain into the same transaction as restore writes
- [x] 4.4 Add rollback-capable SettingsStore staging or snapshot restoration so a failed SQL/settings apply restores pre-restore settings
- [x] 4.5 Add fault-injection tests for source-load failure, validation failure, failure after clear, failure during domain writes, and settings apply failure
- [x] 4.6 Verify every failure path leaves favorites, settings, all history/progress tables, update events, and filter preferences unchanged

## 5. Merge And Overwrite Semantics

- [x] 5.1 Implement Merge identity and later-progress timestamp selection for tag-catalog, RSS-search, RSS-catalog, and chapter state records
- [x] 5.2 Preserve and regression-test current Merge behavior for existing favorites, settings, notes, bookmarks, histories, and reading-time statistics
- [x] 5.3 Deduplicate update events by the shared canonical `syncId`, preserve valid stored identity, apply legacy ambiguous fallback only when required, and merge `readAt`/`dismissedAt` using the later non-null timestamp
- [x] 5.4 Restore fid enablement by stable fid and category enablement only through category `syncId`; skip unresolved filters with warning/count and rebuild derived rows
- [x] 5.5 Implement Overwrite replacement for all included domains while keeping excluded runtime/cache domains outside imported file data
- [x] 5.6 Add Merge/Overwrite tests for older/newer progress, same-source events with differing summary/time, distinct upstream evidence, legacy ambiguous fallback, remapped category ids, orphan-filter warnings, and stale local rows

## 6. UI And Reporting

- [x] 6.1 Extend `BackupRepository.RestoreSummary` with counts for all reading/history records and favorite-update events
- [x] 6.2 Update the local backup page title/subtitle or concise scope text to mention settings, favorites, history/progress, and update records
- [x] 6.3 Update success feedback to report favorites, settings, reading/history records, and update-event counts without adding a new restore mode
- [x] 6.4 Add UI-state/string tests for the expanded scope and restore summary in light and dark Yamibo themes

## 7. Validation

- [x] 7.1 Run focused backup codec, canonical-snapshot, SQLDelight, repository restore, fault-injection, and UI-state tests
- [x] 7.2 Run shared and Compose Android unit suites and report their exact scope/results
- [x] 7.3 Compile shared and Compose iOS simulator targets
- [x] 7.4 Validate `expand-local-backup-history-updates` with strict OpenSpec validation and run `git diff --check`
