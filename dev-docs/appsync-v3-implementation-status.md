# AppSync storage/recovery implementation checkpoint

Updated: 2026-09-14. This is an implementation checkpoint, not a release approval.

## Implemented, under integration verification

- Persist retry identity/count/deadline; stop on the third consecutive failure for the
  same immutable target. Explicit resume resets only retry exhaustion, not policy failures.
- Reconcile segment/root/index writes through provider discovery before resubmitting;
  distinguish discovery failure from an authoritative absence. Canonical wrapper hashing
  tolerates provider HTML whitespace normalization. Reject non-advancing pagination.
- Migration 43 adds a session-bound immutable envelope, including its kind, identity,
  and fingerprint. Journal, legacy-recovery, and checkpoint publication reuse that envelope
  across restarts instead of regenerating observation/acknowledgement metadata. All existing
  segment intents are checked before any new provider write. Session rollback/removal deletes
  its envelope too; a completed session retains it until that session is retired, not as
  payload-free audit metadata.
- A segmented journal resumes its original source set and leaves later mutations pending.
  Legacy recovery resumes its staged replacements without replanning against newer discovery
  evidence. Migration 44 adds durable source-to-replacement mappings for additional pending
  mutations. Verified activation atomically copies these into the new writer after the recovery
  head, remaps winning field/tombstone/relation provenance, advances the writer and causal
  watermark, and completes the session. Ordinary local data is not rematerialized. Original
  source bodies/lifecycles remain intact; the mapping survives session removal for future
  coverage-based pruning. Completed activation is idempotent.
- Journal roots are not rewritten as ordinary journals on the next smaller upload. Later
  pending operations and metadata-only updates continue through new segmented root/index commits.
- Android manual synchronization enqueues unique WorkManager work independently of the
  automatic-sync preference. Persisted retry deadlines prevent early duplicate attempts.
- UI reports waiting deadlines and a manual resume action; metadata polling uses SQL
  pending-row counts instead of deserializing every pending operation.
- Explicit portable setting allowlist for all three production settings registries.
  Unknown settings stay in local preferences and do not create new AppSync projections.
  Existing local-backup inclusion rules are preserved.
- Cover exclusion for known favorite/update/reading domains at recording, snapshot
  migration, reduction, projection persistence, and checkpoint construction. Winning
  operation copies inside checkpoints are sanitized too. The checkpoint writer rejects
  callers that bypass these projection/snapshot exclusions.
- Normal remote materialization retains an existing local cover instead of overwriting it.
  Whole-checkpoint replacement also preserves covers by stable identity across local ID
  reassignment, rolls back atomically on failure, and does not resurrect deleted entities.
  Legacy v2 Put transport keeps required cover keys as null where older readers require them.

## Not complete; do not enable a v3 writer or destructive cleanup

- Full fail-closed **field/domain** registry, numeric IDs, typed values, 512-byte title
  budgets, parent joins, derived identity removal, minimal patches, and shared batch proof.
- General mutation/import/final-wire budget enforcement and redacted exclusion diagnostics.
- Existing persisted projection rebuild and physical pruning after verified full coverage.
- Carry-forward source pruning is deliberately not enabled: retained originals and mapping
  records need transitive verified coverage and payload-free audit retention in the cleanup phase.
- Expand all-phase restart/idempotency coverage, including older sessions without a pinned
  envelope and interrupted checkpoint activation; incompatible legacy segment plans fail closed.
- iOS independent manual recovery scheduling, all-phase restart tests, and Android
  WorkManager end-to-end fault injection (unit/fake-provider tests alone are insufficient).
- V3 compact codec, operation table deduplication, bounded decoding, golden vectors,
  reader-cohort rollout gate, and Android/iOS compression/dependency benchmarks.
- Verified sanitized checkpoint coverage, exact remote retirement after 24 hours and a
  second authoritative observation, seven-day/three-observation orphan policy, payload-free
  30-day audit retention, and SQLite physical reclamation with separate byte metrics.

## Verification commands

Latest checks (2026-09-14): all 474 shared tests and 15 CloudSyncUiState tests passed.
Coverage includes migrations 43/44, frozen-envelope restart, mismatching old segment intents,
atomic carry-forward, transaction failure rollback, closing/reopening an on-disk database,
winning provenance, deletion-proof preservation, and subsequent small/metadata-only root commits.
`git diff --check` and strict OpenSpec validation passed. The debug APK was last assembled and
installed on 2026-09-11; emulator QA was not rerun for this slice because adb reports no devices.
Two of 56 full OpenSpec tasks are checked off (1.5 and 4.5); partial slices above are intentionally
not counted as full acceptance.

Run from this worktree, with TEMP/TMP pointing to its `.gradle-temp` directory when needed:

```powershell
.\gradlew.bat --no-daemon :shared:testDebugUnitTest :composeApp:testDebugUnitTest --tests '*CloudSyncUiStateTest' :composeApp:assembleDebug
openspec validate appsync-v3-storage-recovery --strict
```

The recovery fault suite now covers a verified first segment followed by retryable failures,
respecting a future retry deadline, third-failure exhaustion, explicit resume, HTML-normalized
  ambiguous write reconciliation, and index reuse after a timestamp-changing restart.
Portable boundary tests cover settings-registry completeness, unknown-setting local-only
behavior, all seven cover-bearing snapshot collections, checkpoint provenance sanitization,
writer bypass rejection, local-backup preservation, and incremental local-cover preservation.
Whole-checkpoint tests cover all seven local cover collections, rollback, and removal of
entities absent from a replacement checkpoint.

Android emulator smoke QA used the separate `me.thenano.yamibo.yamibo_app.debug` package
on Pixel_9 / emulator-5554, leaving the existing release package and its account untouched.
The app launches, the cloud settings page renders, and manual sync with automatic sync off
reaches the authentication-required state without an indefinite busy indicator. Crash buffer
was empty. Evidence: `build/qa/appsync/manual-auth.png` and `manual-auth.xml` (local generated
artifacts, not versioned). This is **not** the oversized-account recovery acceptance test.

## Concurrent-mutation issue fixed on 2026-09-14

The new concurrent-mutation regression demonstrated that `getPendingOperations` only queries
the active installation's device/epoch. Rotating the legacy-recovery writer while later local
operations still use the old identity made those operations disappear from the sync queue
even though their rows survived. After user alignment, activation now transfers pending work
atomically into the new writer with durable identity mapping. A failure rolls back new outbox
rows, mappings, provenance, causal watermark, source acknowledgement, and writer/session changes.

Transferred operations retain their fields, generation, timestamp, origin, and deletion proof.
Their causal context retains original evidence and orders them after the recovery head and prior
transferred operations. Original bodies remain until future verified cleanup, ordinary editing
stays available, and the already published envelope remains unchanged. This is local/production-
fake-provider coverage, not a claim of completed WorkManager or device-reboot acceptance.

No cloud retirement/deletion has been performed by this implementation session. No iOS
build or device benchmark is claimed from the Windows host. OpenSpec task checkboxes remain
conservative: partial implementation does not satisfy cross-platform/end-to-end acceptance.
