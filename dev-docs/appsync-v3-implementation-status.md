# AppSync storage/recovery implementation checkpoint

Updated: 2026-09-18. This is an implementation checkpoint, not a release approval.

## Implemented, under integration verification

- A pure legacy-operation importer validates account/key/derived-field agreement, existing
  contracts, portable budgets and proof extraction. Accepted/Excluded/NoOp/NeedsAttention
  remain distinct so a future recovery adapter cannot silently lose source sequence coverage.
  Custom favorite-update discriminators are preserved as essential identity evidence; only
  immutable-detail defaults are omitted. Ambiguous legacy identity digest migration remains
  incomplete. See [import boundary and limits](appsync-v3-import.md).
- Canonical operation blocks encode explicit kind/origin/domain/field IDs, typed fields,
  conflict metadata and authorization references with one account binding. A sorted string
  table deduplicates profitable repeated identifiers and text without losing scalar types.
  Decoding checks tuple arity, indexes, canonical ordering, expected account and both encoded
  and expanded budgets. Revision 2 uses typed entity keys for all 19 domains and shared delete
  proof tuples. See [operation block scope and wire layout](appsync-v3-operation-block.md).
- Canonical checkpoint roots store each winning operation once, with field/relation/tombstone
  indexes, exact identity validation and coverage checks. No nested snapshot is encoded.
  Tests preserve subsequent reducer outcomes and round-trip all 19 corpus domains.
  See [checkpoint format and integration limits](appsync-v3-checkpoint.md).
- Canonical journal roots preserve writer identity, ranges, observed/acknowledged coverage,
  heartbeat, app/protocol versions and optional published-through watermarks. Document readers
  compose transport and root validation before returning either document. Production dispatch,
  legacy adapters and materialization remain pending. See [journal contract](appsync-v3-journal.md).
- V3 envelope foundation defines stable schema/codec/compressor IDs, account/kind/identity
  binding, canonical fingerprint, declared length, and header/compressed-byte integrity.
  Strict framing/Base64 and bounded gzip decoding reject malformed or oversized transport.
  VerifiedBytes still requires full tuple/schema validation before reduction; publication and
  capability advertisement remain disconnected. See [v3 envelope contract](appsync-v3-envelope.md).
- Explicit v1/v2 field inventory for all 19 current domains. Unknown domain/field combinations
  fail closed in recording, legacy classification, reduction, and checkpoint projections.
  Single/batch/command mutations still run locally; excluded-only patches allocate no new
  operation sequence. Historical empty patches do not create unmaterializable empty entities.
  Local-only setting markers retain the existing redacted cleanup/diagnostic path without values.
  Batch-delete authorization counts only portable entities and keeps v2 embedded proof fields.
  Derived identities and parent labels remain declared pending compatible reconstruction.
  See [legacy boundary scope and remaining gates](appsync-legacy-field-boundary.md).
- Entity-scoped delta recording runs inside the local mutation transaction. Existing live
  Patch fields are compared by canonical scalar value; no-ops execute the local callback without
  allocating an outbox sequence. Required v2 history/subscription fields remain for old readers.
  Ordinary Delete bodies are empty; authorized deletes retain only proof and RelationRemove
  retains legacy identity fields. Repeated targets use private reducer state with prospective
  operation identities and retain contiguous sequences after no-op suppression; no intermediate
  projections are persisted. Setting no-ops preserve the existing winner/timestamp instead of becoming a
  pending-bootstrap projection. Puts and generation/tombstone boundaries retain their semantics.
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

- Full classified canonical registry, numeric IDs, typed values, 512-byte title
  budgets, parent joins, derived identity removal, v3 minimal relation bodies, and shared batch proof.
- General mutation/import/final-wire budget enforcement and redacted exclusion diagnostics.
- Existing persisted projection rebuild and physical pruning after verified full coverage.
- Carry-forward source pruning is deliberately not enabled: retained originals and mapping
  records need transitive verified coverage and payload-free audit retention in the cleanup phase.
- Expand all-phase restart/idempotency coverage, including older sessions without a pinned
  envelope and interrupted checkpoint activation; incompatible legacy segment plans fail closed.
- iOS independent manual recovery scheduling, all-phase restart tests, and Android
  WorkManager end-to-end fault injection (unit/fake-provider tests alone are insufficient).
- Production v3 document integration, parent reconstruction, cross-platform golden vectors,
  reader-cohort rollout gate, and Android/iOS compression/dependency benchmarks.
- Verified sanitized checkpoint coverage, exact remote retirement after 24 hours and a
  second authoritative observation, seven-day/three-observation orphan policy, payload-free
  30-day audit retention, and SQLite physical reclamation with separate byte metrics.

## Verification commands

Canonical restoration now includes four pure field tests and five real SQLite materializer
tests: all 19 domains, checked RSS parent joins, presentation placeholders without fabricated
provenance, event identity reconstruction, dependency order, replay, transaction rollback,
account rejection, missing relation parents, body-free deletion/removal, and local-cover retention.
DatabaseSyncDomainMaterializer exposes applyCanonicalProjections. Migration 45 and
SqlDelightCanonicalCheckpointState now persist raw canonical provenance with atomic projection
replacement and SHA-256 read checks. Six additional tests verify migration, file reopen,
idempotent replay, inner/outer rollback, account/coverage/identity guards, corruption rejection,
and preserved local edits/covers. The new SQL domain is explicitly excluded from portable backup
as synchronization infrastructure. Production engine routing and incremental canonical state
updates are still pending. Tasks 2.5/2.7 remain partial.

OperationReducer now offers canonical reduction through its existing causal conflict core.
Five tests cover all-domain legacy winner equivalence, replay, concurrent monotonic progress,
generation rules, minimal RSS/history patches, event mutation restrictions, operation identity
collisions, account binding, and body-free remove-wins with shared proof. Canonical progress Put
requires its non-null essential values. Reduction retains original canonical winner objects and
does not advance coverage or acknowledge operations. See appsync-v3-reduction.md; pending legacy
import outcomes, contiguous coverage and production engine policy gates still need integration.

Pending merge preparation now connects legacy import outcomes to canonical reduction and
contiguous per-replica coverage, including explicit Excluded/NoOp sequences. Six tests cover
full-corpus migration, multiple replicas, gaps, budgets, oversized essentials, account/operation/
proof collisions, replay, and bulk-delete authorization on an empty installation. The canonical
subset guard preserves original proof count; the legacy guard retains exact-count behavior.
Preparation neither acknowledges nor mutates source rows. See appsync-v3-pending-merge.md;
production engine routing remains pending.

Production remote HTML extraction now preserves v3 envelope line boundaries and escaped metadata
instead of flattening them. Typed dispatcher reads distinguish legacy/v3 journal/checkpoint,
unsupported versions and invalid documents, with expected bindings checked in segmented roots
and inner documents. Five new tests cover HTML wrappers, legacy compatibility, duplicate/corrupt
frames, missing segments and owner/account mismatches. Typed remote load models and engine
activation remain pending; this is not a completed v3 reader rollout. See appsync-v3-reader.md.

An index-bound evidence factory and transactional activation adapter now preserve pending
edits while keeping verified remote coverage distinct from local overlay coverage. Five SQLite
tests prove source lifecycle preservation, replay/new edits, full rollback after nested adoption,
preference reconciliation retry, import failure and account rejection. Three index tests cover
exact binding, ambiguous references and corrupt/unsupported documents. Production engine and
incremental local mutation routing remain incomplete. See appsync-v3-activation.md.

Canonical state now supports one provenance/coverage update per local command without replaying
materialized data or external preferences. Six SQLite tests cover outbox rollback, replay,
batch-independent identities, explicit excluded sequence coverage, account checks and import
failure preserving source evidence. Recorder routing and persistent failure handling still need
integration; the current BLOB rewrite is not evidence of the device write-amplification gate.
See appsync-v3-state.md.

Latest checks (2026-09-18): all 611 shared tests and 15 CloudSyncUiState tests passed, with
zero failures, errors, or skipped tests. This includes canonical field/scalar tests, synthetic
corpus replay, production setting default round trips, isolated SQLite storage accounting,
and the existing recovery, production-producer, backup, and convergence suites. Eight additional
delta-recording tests cover cross-device restoration, scalar-equivalent no-ops, absent/null
distinction, repeated-target batches, v2-required fields, setting provenance (including signed
zero), delete/proof bodies, and rollback of a no-op local database callback.
Seven subsequent batch tests cover repeated targets/commands, reducer winners, recreation,
invalid operations, rollback/retry and malformed excluded keys. Two historical v1/v2 tests
cover unknown data and duplicated checkpoint covers/identity/parent/provenance, preserving
raw source evidence while proving reduction/rebuild exclusions and writer rejection.
Eight v3 envelope tests cover deterministic round trips, exact size limits, unsupported
versions, context binding, strict framing/Base64, bounded expansion, gzip/hash corruption,
and UTF-8 metadata validation. These are host unit tests, not cross-platform golden acceptance.
Ten operation-block tests cover fixed bytes, canonical ordering, all scalar/kind/origin values,
causal and authorization identity preservation, profitable string references, multi-byte indexes,
truncation/corruption, expected account checks, encoded/expanded limits and policy enforcement.
The original field codec's no-table byte vector still passes unchanged.
Subsequent coverage adds three entity-key tests, two shared-proof tests, seven checkpoint
tests, five journal-root tests and three document-reader tests. The revised synthetic corpus
uses the production delimiter for its deleted note identity. Canonical checkpoint evidence
reports 5,371 raw bytes, 57 winning operations and 331 field references; this does not prove
production adapter completeness or platform benchmark acceptance.
Seven importer tests cover all 19 domains, source immutability, explicit exclusion/no-op outcomes,
identity disagreement, scalar-equivalent identity values, title/note budgets, delete proofs,
custom discriminator retention and protection of not-yet-migratable ambiguous identity evidence.
Coverage includes migrations 43/44, frozen-envelope restart, mismatching old segment intents,
atomic carry-forward, transaction failure rollback, closing/reopening an on-disk database,
winning provenance, deletion-proof preservation, and subsequent small/metadata-only root commits.
`git diff --check` and strict OpenSpec validation passed. The debug APK was last assembled and
installed on 2026-09-11; emulator QA was not rerun for this slice. The last adb check on
2026-09-14 reported no devices; device availability was not rechecked on 2026-09-18.
Eight of 56 full OpenSpec tasks are checked off (1.1, 1.3, 1.4, 1.5, 1.6, 3.1, 3.3, and 4.5); partial slices above
are intentionally not counted as full acceptance. See [the baseline notes](appsync-v3-baseline.md)
for corpus sizes, host-only measurements, accounting definitions, and remaining device gates.

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

No cloud retirement/deletion has been performed by these implementation sessions. No iOS
build or device benchmark is claimed from the Windows host. OpenSpec task checkboxes remain
conservative: partial implementation does not satisfy cross-platform/end-to-end acceptance.
