## 1. Rollout Evidence Harness

- [x] 1.1 Add a bounded rollout result model that records eligible demands, successes, retries, typed exclusions, manual interventions, and acknowledged-operation loss
- [x] 1.2 Add a deterministic fixed-point verifier covering pending operations, quarantines, applied causal state, retained tombstones, and materialized projections
- [x] 1.3 Add privacy-safe evidence serialization that excludes raw content, cookies, FormHash, account identifiers, blog payloads, and database files
- [x] 1.4 Add a controlled provider/fault matrix with at least 100 eligible demands across duplicate delivery, reordering, retry, restart, checkpoint adoption, force recovery, and failed authoritative load

## 2. Controlled Reliability Gate

- [x] 2.1 Exercise favorites, settings, reading/history, FavoriteUpdate events, lifecycle markers, and filter choices in the controlled demand matrix
- [x] 2.2 Exercise representative additions and removals from two through five replicas, including old Put replay after tombstones and reset-looking replicas
- [x] 2.3 Prove failed authoritative cloud loads and stale force confirmations produce zero local portable-state mutation
- [x] 2.3a Add `rss.search-subscription` stable identity, mutation recording, backup/bootstrap projection, materialization, tombstones, and cache exclusion after live seeding exposes the missing domain
- [x] 2.4 Report at least 100 eligible demands, at least 99% fixed-point convergence, zero acknowledged-operation loss, and all exclusions without counting retries as new demands

## 3. Data-Preserving Android Setup

- [x] 3.1 Record pre-install package version, database schema, semantic table counts/fingerprints, login availability, and connected emulator identities without copying secrets
- [x] 3.2 Build and install the current debug APK with `adb install -r -t`, never clear/uninstall/downgrade the package, and verify pre-existing data survives process restart
- [x] 3.3 Start or provision a second compatible AVD with an independent app database and verify both replicas can access the same configured cloud without exposing credentials
- [x] 3.4 Verify foreground and WorkManager/background execution on both replicas, including process and emulator restart

## 4. Representative Product Seeding

- [x] 4.1 On Device A, locally favorite the first ten threads from Home > 管理版 page 1 without invoking Yamibo website favorite submission
- [x] 4.2 On Device A, locally favorite tag `https://bbs.yamibo.com/misc.php?mod=tag&id=21661` and the RSS search result for `app`
- [x] 4.3 Read a representative subset of local favorites to create history/progress and run Updates global refresh to create FavoriteUpdate records
- [x] 4.4 Change representative syncable settings and capture privacy-safe pre-sync semantic fingerprints

## 5. Live Bidirectional Canaries

- [x] 5.1 Sync Device A additions to the AppSync blog and verify Device B converges without duplicates, unexpected deletes, or any Yamibo website favorite mutation
- [x] 5.2 Create independent Device B setting, history/progress, update read/dismiss, and update-filter changes and verify Device A converges
- [x] 5.3 Remove representative favorites, history/progress, FavoriteUpdate records, and filter choices through product commands and verify tombstone convergence in both directions
- [x] 5.4 Exercise duplicate/reordered delivery, process restart, background execution, and force recovery while retaining all acknowledged winners or tombstones
- [x] 5.5 Bind a fresh/default-looking independent local state to populated verified cloud and prove cloud adoption occurs before local migration publication with no inferred deletes
- [x] 5.6 Record live transport results separately from controlled evidence, including provider failures and retry outcomes

## 6. Retirement and Regression

- [x] 6.1 Publish the bounded rollout report with numerator, denominator, retries, exclusions, interventions, domain coverage, both device fingerprints, and acknowledged-loss count
- [x] 6.2 Remove only the abandoned routine cloud snapshot Merge/Overwrite decision implementation after every controlled and live gate passes
- [x] 6.3 Preserve manual `BackupModels` Merge/Overwrite restore, rollback-readable journals/checkpoints, and failed-load non-mutation behavior
- [x] 6.4 Run focused AppSync/backup tests, complete shared and Compose Android suites, iOS simulator compilation, APK assembly, strict OpenSpec validation, and `git diff --check`
- [x] 6.5 Mark parent task 11.6 complete only after the report demonstrates at least 99% eligible convergence, zero acknowledged loss, two-device coverage, and all safety invariants
