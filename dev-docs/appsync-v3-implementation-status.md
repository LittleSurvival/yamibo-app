# AppSync storage/recovery implementation checkpoint

Updated: 2026-09-11. This is an implementation checkpoint, not a release approval.

## Implemented, under integration verification

- Persist retry identity/count/deadline; stop on the third consecutive failure for the
  same immutable target. Explicit resume resets only retry exhaustion, not policy failures.
- Reconcile segment/root/index writes through provider discovery before resubmitting;
  distinguish discovery failure from an authoritative absence. Canonical wrapper hashing
  tolerates provider HTML whitespace normalization. Reject non-advancing pagination.
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
- Persist the exact recovery envelope across changing observed/acknowledgement metadata
  and changes to the pending source set; expand restart/idempotency fault coverage.
- iOS independent manual recovery scheduling, all-phase restart tests, and Android
  WorkManager end-to-end fault injection (unit/fake-provider tests alone are insufficient).
- V3 compact codec, operation table deduplication, bounded decoding, golden vectors,
  reader-cohort rollout gate, and Android/iOS compression/dependency benchmarks.
- Verified sanitized checkpoint coverage, exact remote retirement after 24 hours and a
  second authoritative observation, seven-day/three-observation orphan policy, payload-free
  30-day audit retention, and SQLite physical reclamation with separate byte metrics.

## Verification commands

Latest completed checks: 465 shared tests and 15 CloudSyncUiState tests passed; Android
debug APK assembled and installed successfully; `git diff --check` and strict OpenSpec
validation passed. Two of 56 full OpenSpec tasks are checked off (1.5 and 4.5); the remaining
implementation slices above are intentionally not counted as full acceptance.

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

No cloud retirement/deletion has been performed by this implementation session. No iOS
build or device benchmark is claimed from the Windows host. OpenSpec task checkboxes remain
conservative: partial implementation does not satisfy cross-platform/end-to-end acceptance.
