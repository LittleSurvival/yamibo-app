## Why

The local "收藏與設定備份" already protects favorites, settings, notes, bookmarks, thread/image/tag-manga reading history, and reading-time statistics, but its coverage has drifted behind newer reading-history tables and excludes durable favorite-update records. A coverage-audited contract is needed so the UI, restore summary, compatibility tests, and implementation all describe the same recoverable user data without treating runtime scan state as user-owned backup data.

## What Changes

- Audit and contract-test the existing local backup scope instead of replacing its working reading-history support.
- Add the actually missing `TagCatalogReadingHistory`, `RssSearchReadingHistory`, `RssCatalogReadingHistory`, and durable `LocalChapterState` reading-progress records to local export, validation, restore, overwrite clearing, and restore summaries.
- Add durable favorite-update events, including their canonical `syncId`/`sourceFingerprint` identity and read/dismissed state, to local export and restore.
- Preserve user-selected favorite-update fid/category filter enablement while rebuilding derived labels, counts, and category-local references from restored favorites.
- Explicitly exclude favorite-update scan baselines, tracked-target fingerprints, run/progress/log state, scheduler state, transient errors, and other rebuildable caches.
- Keep older backup files decodable through default-empty additive fields and preserve unknown-field tolerance.
- Require complete decode, schema checks, relationship validation, and bounded payload validation before any destructive restore mutation; apply SQL-owned data atomically.
- Update the local backup UI description and restore result so users can see that history and update records are included.

## Capabilities

### New Capabilities

- `local-backup-restore`: Defines the complete user-owned local backup scope, compatibility contract, merge/overwrite behavior, atomicity, and user-visible restore reporting.

### Modified Capabilities

None.

## Impact

- Backup boundary models, canonical snapshot scope declarations/adapters, `BackupRepositoryImpl`, restore summaries, and local backup UI copy.
- SQLDelight queries for complete reads and transactional restore/clear of the three newer history families and favorite-update durable state.
- Backup fixtures, codec/migration tests, repository transaction tests, coverage-manifest tests, and UI-state tests.
- AppSync checkpoint creation must select its declared manifest scope: FavoriteUpdate remains excluded until the separate cloud capability registers it, after which the one shared FavoriteUpdate projection is included exactly once without a duplicate transport/model.
