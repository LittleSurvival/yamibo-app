## Implementation Audit

This is an implementation map for the current worktree. It changes no production code.

## Task Map

| Task | Files / APIs | Executable change |
| --- | --- | --- |
| 1.1 | `shared/src/commonMain/sqldelight/.../34.sqm`, `FavoriteUpdateEvent.sq` | Add nullable physical `syncId`, `sourceFingerprint`, `sourceDiscriminator`, partial unique indexes, and get/set/upsert-by-sync-id queries. Keep local autoincrement `id`. Kotlin backfill enforces logical non-null. |
| 1.2 | `34.sqm`, new `FavoriteUpdateFidChoice.sq`, `FavoriteUpdateCategoryChoice.sq` | Add durable choice tables keyed by FID/category sync id with `enabled`, nullable winner operation id, and diagnostic update time. Existing filter tables stay derived projections. |
| 1.3 | shared FavoriteUpdate identity utility (replace/move current `repository/backup/FavoriteUpdateEventIdentity.kt`) | One canonical discriminator/fingerprint implementation for detector, backfill, backup, registry, tests. |
| 1.4 | identity utility, new backfill helper, `AppSyncService` init | Backfill ids; edited events use upstream revision time, ambiguous new events use scan evidence, legacy ambiguous rows use retained content/time. Verify null/duplicate failures. |
| 1.5 | backfill helper, `LocalFavoriteCategory.sq`, choice queries | Copy existing filter choices; category uses `LocalFavoriteCategory.syncId`; skip unresolved orphan rows. |
| 1.6 | new `AppSyncMigration34Test.kt` | Populated/read/dismissed, edited, ambiguous, orphan, empty, repeated-init cases. |
| 2.1 | `SyncDomainRegistry.kt` | Register event, FID choice, category choice domains in required coverage. |
| 2.2 | `SyncDomainRegistry.kt`, identity utility | Add allowed-field/domain semantic validator; verify identity, types, marker values, and reject immutable-field Patch. |
| 2.3 | `SyncDomainRegistry.kt` | Require canonical FID/category entity ids and boolean values; reject local category id, labels, counts. |
| 2.4 | `SyncDomainRegistry.kt`, existing `OperationReducer.kt` | Event uses `RemoveWinsEntity`; Put omits null lifecycle markers; reuse causal/tie/generation reducer behavior. |
| 2.5 | `OperationReducerTest.kt`, new registry tests | Coverage, quarantine, marker races, filter races, tombstone replay, permutation determinism. |
| 3.1 | `FavoriteUpdateRepositoryImpl.kt`, `AppSyncService.kt`, `MainActivity.kt`, `MainViewController.kt`, `AndroidFavoriteUpdateSupport.kt` | Add recorder injection and `AppSyncService.favoriteUpdateRepository(...)`; route foreground and Android worker/cancel construction through it. |
| 3.2 | repository, `FavoriteUpdateEvent.sq`, `AppSyncMutationRecorder.recordCommand` | Insert stable event and return Put draft only when row was absent. Duplicate detection is local/outbox no-op. |
| 3.3 | repository/event SQL | Resolve local id, perform first null-to-value read/dismiss transition, return one Patch atomically. |
| 3.4 | repository/event SQL, `recordCommand` | Enumerate changed rows for batch/all dismiss and return all patches in one transaction. |
| 3.5 | repository, choice SQL | Toggle durable choice and append operation atomically; refresh emits no operation. |
| 3.6 | repository refresh methods, choice SQL | Overlay durable choices on derived rows; retain unresolved category choices. |
| 3.7 | `ManualSyncOverrideCoordinator.kt`; no new public purge API | Normal absence/reset/refresh/restore never emits Delete. Only explicit force-authoritative/purge paths do. |
| 3.8 | new `FavoriteUpdateRepositoryImplTest.kt` | Duplicate, disabled/unbound, rollback, retry, batch, refresh, remapping, worker wiring. |
| 4.1 | in-flight `BackupModels.kt`, `BackupRepositoryImpl.kt`, `PortableDomainManifest.kt` | Reuse one FavoriteUpdate section; add discriminator; export durable choices, not filter projections; exclude run/tracked state. |
| 4.2 | backup implementation/tests | Old defaults, validation, round-trip, Merge/Overwrite; backup restore never creates cloud deletes. |
| 4.3 | `BackupSnapshotMigrationPlanner.kt` | Emit stable event/FID/category Put drafts. |
| 4.4 | `BootstrapCoordinator.kt` tests | Reuse cloud-first adoption; drop local draft only for matching domain/entity. |
| 4.5 | backup/bootstrap tests | Cloud match wins, unique local migrates, empty/reset emits no delete/default overwrite. |
| 5.1 | `DatabaseSyncDomainMaterializer.kt`, event SQL | Apply/delete by sync id while preserving existing local UI id. |
| 5.2 | materializer, choice SQL, `reconcileProjections()` | Apply choice entities and then overlay resolvable projections. |
| 5.3 | `DatabaseSyncDomainMaterializer.clearSyncableData()` | Clear events/choices; preserve `FavoriteUpdateRun` and `FavoriteUpdateTrackedTarget`. |
| 5.4 | new checkpoint projection validator, `CheckpointCoordinator`, checkpoint adoption path | Compare snapshot with latest-generation FavoriteUpdate entities/tombstones before publish/adopt. Current codec only validates envelope structure. |
| 5.5 | materializer/checkpoint Android tests | Arrival order, markers, tombstone, rollback, local-only preservation. |
| 6.1 | `ManualSyncOverrideCoordinator.kt` | Include new entities in semantic/stale fingerprints; payload design excludes local/derived fields. |
| 6.2 | `OperationChangeSummary.kt`, `AppSyncService.kt`, `CloudSyncUiContract.kt` | Add Read/Dismissed actions/counts and three module labels. |
| 6.3 | force coordinator/materializer | Force push uses normal operations/authorization; force pull replaces only syncable update state. |
| 6.4 | `ManualSyncOverrideCoordinatorTest.kt` | FavoriteUpdate stale token and failed-load no-mutation cases. |
| 6.5 | `CloudSyncUiStateTest.kt`, screen/controller tests | Grouped differences before existing 10-second confirmation. |
| 7.1 | new common convergence tests | 2-5 replicas, same-event detection, marker/filter races, duplicate/reordered delivery, tombstone replay. |
| 7.2 | new Android SQLDelight integration test | Interleave scan command, remote materialization, force pull. |
| 7.3 | envelope/checkpoint/backup/force tests | Assert run/tracked fields never serialize or summarize. |
| 7.4 | focused Gradle filters | Migration, repository, registry/reducer, bootstrap, materializer, checkpoint, force, backup. |
| 7.5 | full Gradle/OpenSpec/git checks | Shared/Compose Android tests, iOS simulator compile, APK, strict OpenSpec validation, `git diff --check`. |
| 7.6 | two emulators, `adb install -r -t` | Detect/converge/read/dismiss/filter both ways; preserve DB across restart; never touch Yamibo server favorites. |

## Merge, Tombstone, and 90-day Rules

1. Put/Patch fields resolve causally; concurrent scalar ties use operation id, never wall-clock freshness.
2. `readAt`/`dismissedAt` are one-way null-to-value facts. Put omits null markers; retries emit nothing.
3. Same-generation Delete is remove-wins. Old/reordered Put cannot resurrect it.
4. Detector never recreates a tombstoned event. Only confirmed, non-stale force push may use a new generation.
5. Missing local/snapshot/filter rows mean no opinion except during explicitly confirmed force override.
6. Filter choices sync by FID/category sync id; labels/counts/local category ids stay local.
7. Ninety days is the active-replica horizon, not an event TTL. Events do not expire by age.
8. Covered operations compact only through an authoritatively verified checkpoint acknowledged by every device active within 90 days.
9. A device returning after 90 days must adopt the retained checkpoint and rotate epoch before publishing.

## Minimum Test Matrix

| Layer | Minimum cases |
| --- | --- |
| Identity/migration | normal ids; edited revision; ambiguous observation; legacy ambiguous; duplicate fail-closed; markers; orphan category; rerun |
| Registry/reducer | invalid schema/id; Put+read/dismiss races; filter race; Delete replay; new-generation force recreation; permutations |
| Repository/outbox | new/duplicate detection; first/repeated read; single/batch/all dismiss; rollback; disabled/unbound; refresh no-op; worker wiring |
| Backup/bootstrap | old decode; round-trip; cloud match wins; unique local migrates; reset emits nothing; transient exclusion |
| Materializer/checkpoint | preserve local id; unresolved category then resolve; tombstone; rollback; projection mismatch rejection |
| Force/UI | semantic diff; read/dismiss; enable/disable; stale token; failed load; 10-second gate |
| Convergence/retention | 2-5 devices; reorder/duplicate; scan/apply interleave; no event TTL; 90-day acknowledgement; inactive rebootstrap |

## Risks / Uncertainties

- Edited known posts currently reuse the post id; without upstream `latestUpdateMillis` in the discriminator, separate edits collapse.
- New ambiguous tag insertion currently lacks canonical scan evidence. It must receive page fingerprints; local `detectedAt` is insufficient for cross-device dedupe.
- Legacy ambiguous rows cannot always dedupe across independently migrated devices; preserve rather than collapse them.
- The shared 64-bit fingerprint is not cryptographic. Backfill must fail closed if the same sync id maps to different canonical material.
- The in-flight local-backup change already owns FavoriteUpdate backup models but lacks `sourceDiscriminator` and excludes them from AppSync snapshots. Rebase/amend those files; do not create parallel models.
- Android `FavoriteUpdateWorker` currently builds a direct repository and would bypass AppSync recording unless construction is routed through `AppSyncService`.
- Checkpoint snapshot/entity consistency is currently unvalidated; task 5.4 is mandatory before claiming checkpoint coverage.
- Current spec text says no retention policy. Interpret that only as no event-age purge; the existing 90-day active-replica/checkpoint rules still apply.
