## ADDED Requirements

### Requirement: Transactional local operation outbox
The system SHALL represent every syncable local mutation as a durable operation with a stable operation id, device id, device epoch, monotonic sequence, domain id, stable entity id, operation kind, causal context, schema version, and diagnostic timestamp. The domain mutation and outbox append SHALL commit atomically, and retries SHALL reuse the same operation id.

#### Scenario: Local mutation commits
- **WHEN** a user changes a syncable value while offline
- **THEN** the domain state and exactly one pending operation commit together before the change is reported successful

#### Scenario: Process stops during mutation
- **WHEN** the process terminates before the local transaction commits
- **THEN** neither the domain mutation nor its outbox operation is visible after restart

#### Scenario: Operation retry
- **WHEN** publication is retried after an unknown network result
- **THEN** the system republishes the original operation id instead of creating a duplicate logical mutation

### Requirement: Per-device single-writer journals
The system SHALL store each active device epoch's operations in a distinct private Yamibo journal blog and SHALL NOT edit another device epoch's journal. The journal envelope SHALL identify its device epoch, writer instance, sequence range, operations, causal acknowledgements, schema, and fingerprint.

#### Scenario: Two devices publish concurrently
- **WHEN** two devices have pending operations at the same time
- **THEN** each device writes only its own journal and neither write replaces the other device's operations

#### Scenario: Restored clone reuses device metadata
- **WHEN** an installation observes that its journal was last written by a different writer instance
- **THEN** it rotates to a new device epoch before publishing

### Requirement: Advisory config and discovery index
The system SHALL treat the config/index blog as an advisory account marker and discovery cache, not as authoritative evidence that an operation, journal, or device was deleted. It SHALL cache verified blog ids and perform full journal discovery only on missing/invalid identity, unknown references, explicit repair, or after a 24-hour discovery interval.

#### Scenario: Cloud-sync page opens with valid cache
- **WHEN** the user opens the cloud-sync page and verified discovery is less than 24 hours old
- **THEN** the page uses cached status and does not immediately scan every user-space blog page

#### Scenario: Concurrent index update loses a journal reference
- **WHEN** a valid journal is absent from the latest index payload
- **THEN** the system retains any cached/discovered reference and does not delete or disregard that journal

#### Scenario: Cached journal is missing
- **WHEN** a cached journal id produces an authoritative missing or wrong-marker result
- **THEN** the system schedules full discovery without deleting local data or other cloud blogs

#### Scenario: Status reload uses a valid index
- **WHEN** the user reloads cloud status and the newest index validates successfully
- **THEN** the system merges indexed identities with previously verified local links without treating stale deleted list entries as missing index coverage, and does not perform duplicate index fetch, forced retirement discovery, or no-op publication

### Requirement: Idempotent pull and transactional apply
The system SHALL validate incoming journal identity, envelope integrity, account binding, operation schema, and sequence continuity before reduction. It SHALL deduplicate operations by operation id and commit applied-operation markers, causal high-watermarks, and domain changes in the same transaction.

#### Scenario: Duplicate operation arrives
- **WHEN** the same operation id is present in multiple retries or pull cycles
- **THEN** its domain effect is committed at most once

#### Scenario: Process stops while applying remote operations
- **WHEN** the process terminates before the apply transaction commits
- **THEN** neither the domain effects nor their applied/high-watermark markers advance

#### Scenario: One journal is malformed
- **WHEN** one journal fails envelope or sequence validation while other journals are valid
- **THEN** the invalid journal version is quarantined and valid journals continue to apply

### Requirement: Verified journal publication
The system SHALL mark operations acknowledged when the typed Yamibo POST parser returns `VerifiedSuccess`. The acknowledged journal SHALL be reconstructed from the exact submitted envelope and the known update blog id or the single create blog id parsed from the response. A generic HTTP 200, timeout, ambiguous create id, or unparsed success page SHALL NOT acknowledge operations.

#### Scenario: Typed POST succeeds
- **WHEN** a journal POST returns a parsed Yamibo success prompt and identifies the updated or newly created blog
- **THEN** the exact submitted operations become acknowledged without fetching the same reader page again

#### Scenario: POST result is unknown
- **WHEN** the request times out after the server may have accepted it
- **THEN** operations remain pending or published-unverified and the next run performs the normal authoritative pull before retrying

#### Scenario: Create response does not identify one blog
- **WHEN** a create POST reports success but yields no unique blog id
- **THEN** no operation is acknowledged and the run schedules a retry

### Requirement: Bounded convergence cycle
The system SHALL serialize manual and automatic sync through one durable lease and execute pull, validate, reduce, apply, and publish-own-journal phases. A successful run SHALL leave no pending local operation. Concurrent remote writes that occur after the initial pull SHALL be absorbed by the next scheduled, lifecycle, or manual sync rather than forcing an unconditional second full pull.

#### Scenario: Remote change races with publication
- **WHEN** another device publishes after the initial pull but before the local publication completes
- **THEN** the current local publication may complete and the next eligible sync observes and reduces the remote operation

#### Scenario: Foreground and background triggers overlap
- **WHEN** foreground and platform background sync start concurrently
- **THEN** one run owns the durable lease and the other coalesces its trigger without starting a second publisher

#### Scenario: Retry bound is reached
- **WHEN** the run cannot reach a fixed point within the configured attempts
- **THEN** it persists pending state, releases the lease, and schedules a later retry without discarding operations

### Requirement: Immutable checkpoint compaction
The system SHALL construct uniquely identified immutable checkpoint blogs from a deterministic `BackupModels` state projection and causal vector. Checkpoint creation SHALL require typed POST success and one response blog id. It SHALL prune an operation only after every device active within the last 90 days acknowledges that exact checkpoint and covering vector.

#### Scenario: Checkpoint candidate is written
- **WHEN** a device creates a checkpoint candidate
- **THEN** no journal operation is pruned until typed creation success and active-device acknowledgements complete

#### Scenario: Competing checkpoints exist
- **WHEN** multiple valid checkpoint candidates are created concurrently
- **THEN** devices deterministically select a sufficiently covering candidate and neither candidate overwrites the other

#### Scenario: Storage pressure occurs before safe compaction
- **WHEN** a journal approaches the measured provider limit but pruning prerequisites are incomplete
- **THEN** sync pauses publication with a typed storage-pressure result rather than dropping uncheckpointed operations

### Requirement: Backup model reuse boundary
The system SHALL use existing `BackupModels` and payload adapters for checkpoint/full export projection and manual recovery, but SHALL NOT infer incremental updates or deletes by diffing whole backup snapshots.

#### Scenario: Empty backup projection
- **WHEN** a fresh or reset database produces an almost-empty `BackupModels` projection
- **THEN** the system does not create delete operations or publish the projection as authoritative cloud state
