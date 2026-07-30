## 1. Schema and Stable Identity

- [x] 1.1 Add a SQLDelight migration that gives `FavoriteUpdateEvent` unique non-null `syncId` and `sourceFingerprint` columns and indexes without changing its local autoincrement UI id
- [x] 1.2 Add durable FID and category filter-choice tables keyed by numeric FID and category `syncId`, including winning-operation metadata needed for projection/debugging
- [x] 1.3 Implement one shared canonical event-source fingerprint function for detectors, migration backfill, backup adapters, and tests
- [x] 1.4 Deterministically backfill event identities, using canonical detail ids for normal events and retained observation content for legacy ambiguous events
- [x] 1.5 Backfill FID choices and resolvable category choices from existing filter rows while skipping category rows that lack a stable category `syncId`
- [x] 1.6 Add migration tests for populated, dismissed/read, ambiguous, orphan-category, empty, and repeated-initialization databases

## 2. Domain Registry and Reduction

- [x] 2.1 Register `favorite.update-event`, `favorite.update-fid-filter`, and `favorite.update-category-filter` in the required domain coverage set
- [x] 2.2 Define strict required/allowed fields and semantic validation for event Put/Patch/Delete operations, including non-null parseable lifecycle markers and canonical source identity checks
- [x] 2.3 Define filter Put/Patch validation so category payloads cannot contain local category ids and enabled values accept only canonical booleans
- [x] 2.4 Implement event conflict behavior where non-null read/dismiss markers persist, causal successors win, concurrent values use deterministic operation ids, and Delete remains remove-wins
- [x] 2.5 Add registry coverage, invalid-operation quarantine, event lifecycle, filter conflict, tombstone replay, and deterministic multi-order reducer tests
- [x] 2.6 Add explicit clock-skew tests proving event/filter winners follow causal order and operation-id ties rather than `detectedAt`, `readAt`, `dismissedAt`, or device current time

## 3. FavoriteUpdate Repository Recording

- [x] 3.1 Inject the existing `AppSyncMutationRecorder` boundary into `FavoriteUpdateRepositoryImpl` without changing behavior when AppSync is disabled or unbound
- [x] 3.2 Replace raw event insertion with identity-based idempotent upsert plus atomic creation-operation recording
- [x] 3.3 Record mark-read and single-dismiss patches atomically after resolving local event id to stable sync id
- [x] 3.4 Record batch-dismiss and dismiss-all patches with one transactional command so partial local/outbox commits are impossible
- [x] 3.5 Persist FID/category durable choices and record user toggle operations atomically while keeping automatic filter refresh operation-free
- [x] 3.6 Merge durable choices into derived filter projections, preserving unresolved category choices until their category `syncId` becomes available
- [x] 3.7 Route any explicit physical event purge through authorized Delete operations and ensure row absence/refresh/reset never emits deletes
- [x] 3.8 Add repository tests for duplicate detection, disabled/unbound mode, atomic rollback, retries, batch commands, refresh behavior, and category local-id remapping

## 4. Backup Projection and Bootstrap

- [x] 4.1 Reconcile with the local backup completion OpenSpec and add or reuse exactly one `BackupModels` FavoriteUpdate section containing events/lifecycle and stable filter choices but no run/cache state
- [x] 4.2 Update backup serialization defaults, snapshot creation, restore validation, Merge/Overwrite behavior, and schema migration so old backup files remain readable
- [x] 4.3 Extend `BackupSnapshotMigrationPlanner` to emit FavoriteUpdate migration drafts using event/FID/category stable identities
- [x] 4.4 Ensure bootstrap admits local FavoriteUpdate migrations only after verified cloud adoption and only when the same domain/entity is absent remotely
- [x] 4.5 Add snapshot round-trip and bootstrap tests proving cloud wins matching identities, unique local records migrate, and empty/reset local state emits no overwrite or delete

## 5. Materialization and Checkpoints

- [x] 5.1 Add transactional event materialization by sync id while preserving stable local UI ids and active-list dismissed semantics
- [x] 5.2 Add durable filter-choice materialization and post-transaction projection reconciliation through FID/category sync identity
- [x] 5.3 Extend `clearSyncableData` and checkpoint adoption to replace FavoriteUpdate events/choices while preserving run and tracked-target tables
- [x] 5.4 Extend checkpoint projection/validation so backup FavoriteUpdate data, resolved entities, lifecycle markers, tombstones, and causal coverage agree after authoritative reload
- [x] 5.5 Add materializer/checkpoint tests for out-of-order category arrival, lifecycle patches, tombstones, failure rollback, and local-only table preservation

## 6. Force Overrides and User-Facing Summaries

- [x] 6.1 Extend semantic local/cloud comparison and stale-preview fingerprints to all three FavoriteUpdate domains while excluding local ids and derived fields
- [x] 6.2 Extend change-summary actions and presentation labels for event added, updated, read, dismissed, deleted and filter enabled/disabled results
- [x] 6.3 Ensure force push emits later valid operations/tombstones through the normal registry and force pull replaces only FavoriteUpdate syncable state
- [x] 6.4 Add preview/apply tests proving a FavoriteUpdate change invalidates stale confirmation and failed authoritative loads mutate no local table
- [x] 6.5 Add Compose/controller tests verifying grouped FavoriteUpdate differences appear before the existing 10-second force confirmation can be accepted
- [x] 6.6 Add bounded detail rows to the shared summary model for normal sync, sync details, and force preview: module/action/count, up to five event title/target or filter-label details, remaining count, and safe fallback identity
- [x] 6.7 Add summary tests for every event/filter action, missing display metadata, truncation, localization mapping, internal-id exclusion, and formatting failure without sync failure

## 7. Convergence, Safety, and Regression Validation

- [x] 7.1 Add two-to-five-device model tests for independent same-event detection, read/dismiss races, filter races, duplicate/reordered delivery, and old Put replay after tombstone
- [x] 7.2 Add an interleaving test for FavoriteUpdate scanning during normal cloud materialization and confirmed force pull
- [x] 7.3 Verify transient `FavoriteUpdateRun` and `FavoriteUpdateTrackedTarget` data never appears in operation envelopes, checkpoints, backup FavoriteUpdate payloads, or force summaries
- [x] 7.4 Run focused shared Android tests for migration, repository, registry/reducer, bootstrap, materializer, checkpoint, force override, and backup compatibility
- [x] 7.5 Run complete shared and Compose Android unit suites, iOS simulator Kotlin compilation, Android APK assembly, strict OpenSpec validation, and `git diff --check`
- [x] 7.6 Perform data-preserving emulator validation with `adb install -r -t`: detect an event on one device, converge it to another, sync read/dismiss and filter changes in both directions, and confirm app/database data survives restart
- [x] 7.7 Record a privacy-safe rollout report covering two-device final projection equality, empty/reset bootstrap, process restart, duplicate/reordered retry, background/foreground paths, detailed UI summaries, quarantine/retry counts, and zero unexplained acknowledged-operation loss
