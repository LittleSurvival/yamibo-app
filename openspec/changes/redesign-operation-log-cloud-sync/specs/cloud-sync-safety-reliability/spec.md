## ADDED Requirements

### Requirement: Pull-only bootstrap state
The system SHALL place fresh, reset, metadata-corrupted, account-unbound, or over-90-day-inactive installations into pull-only bootstrap. Such an installation SHALL NOT publish local state until it adopts a verified checkpoint plus later operations, verifies local high-watermarks, and creates a new active device epoch.

#### Scenario: New installation contains defaults
- **WHEN** automatic sync starts on a new installation
- **THEN** it loads and applies cloud state before it can publish default local values

#### Scenario: Database generation changes
- **WHEN** the app detects that sync metadata and the domain database no longer belong to the same generation
- **THEN** it enters bootstrap and does not interpret missing local rows as deletions

#### Scenario: Device returns after 90 days
- **WHEN** a device has no verified cloud heartbeat for more than 90 days
- **THEN** it must bootstrap from a retained checkpoint and rotate epoch before publishing

### Requirement: Preserve local data during failed cloud loading
The system SHALL NOT modify or delete local domain data, local verified blog identity, remote journals, config blogs, or checkpoints because discovery, authentication, network loading, parsing, validation, or provider requests failed.

#### Scenario: Cloud load throws an unknown exception
- **WHEN** any unexpected exception occurs before a validated operation apply plan exists
- **THEN** local and remote data remain unchanged and the run records a typed failure

#### Scenario: Config blog cannot be loaded
- **WHEN** a cached config blog read fails for a non-authoritative reason
- **THEN** the system retains the cached identity and does not create, replace, or delete config data

### Requirement: Authorized bulk deletion safeguard
The system SHALL attach durable scope and count authorization to intentionally confirmed bulk deletes. A destructive batch exceeding both the configured absolute and percentage thresholds without valid authorization SHALL be quarantined before apply or publication, while unrelated operations continue.

#### Scenario: User confirms bulk deletion
- **WHEN** the app's destructive action UI confirms a known bulk scope and emits matching authorization
- **THEN** the resulting tombstones synchronize automatically without a second sync-time prompt

#### Scenario: Unexpected mass deletion appears
- **WHEN** a batch exceeds configured thresholds and has no matching authorization
- **THEN** it is quarantined and cannot alter local or cloud domain state

### Requirement: Durable failure and retry behavior
The system SHALL persist pending operations and sync phase before network work, classify provider/auth/schema/identity/storage failures, and use bounded exponential retry with jitter only for retryable failures. Formhash expiry SHALL pause writes and use the existing manual login-refresh path.

#### Scenario: Transient provider failure
- **WHEN** Yamibo returns a retryable timeout, maintenance, or rate-limit result
- **THEN** pending work remains durable and a bounded delayed retry is scheduled

#### Scenario: Formhash expires
- **WHEN** a write is rejected because the cached type-safe `FormHash` expired
- **THEN** automatic writes pause, no operation is lost, and durable UI status asks the user to refresh login state

#### Scenario: App process is killed
- **WHEN** the process stops during a sync run
- **THEN** the next foreground or background entry recovers the expired lease and resumes from durable operation state

### Requirement: No acknowledged-operation loss
The system SHALL never prune, replace, or forget an acknowledged operation unless a retained verified checkpoint covers it and the active-device acknowledgement protocol is complete. This is a correctness invariant and SHALL NOT be weakened to a percentage objective.

#### Scenario: Checkpoint creation fails
- **WHEN** checkpoint POST is not a typed success or does not identify one created blog
- **THEN** all covered journal operations remain retained and readable

#### Scenario: Active device has not acknowledged checkpoint
- **WHEN** any device active within 90 days has not acknowledged the exact checkpoint vector
- **THEN** no device may prune operations required by that checkpoint

### Requirement: Automatic-sync reliability objective
The system SHALL measure eligible sync demands and achieve a rolling success rate of at least 99%, with fewer than 1% requiring manual conflict intervention. Success SHALL mean reaching a fixed point with all pending operations verified or explicitly quarantined and all fetched valid operations applied.

#### Scenario: Eligible foreground opportunity
- **WHEN** auth is valid, the account is unchanged, schemas are supported, cloud data is not manually damaged, Yamibo accepts authorized requests, and the app has 15 minutes of foreground network opportunity
- **THEN** the demand is included in the reliability denominator and is successful only if it reaches the defined fixed point

#### Scenario: Eligible background opportunity
- **WHEN** the same prerequisites hold and the OS grants two background execution windows within 24 hours
- **THEN** the demand is included in the reliability denominator

#### Scenario: Provider is unavailable for the observation window
- **WHEN** Yamibo cannot accept authorized reads or writes throughout the observation window
- **THEN** the demand is reported in raw availability telemetry but excluded from the eligible convergence denominator

#### Scenario: Concurrent values have no objective newest record
- **WHEN** two same-field edits are causally concurrent
- **THEN** success is deterministic convergence with retained loser history, not a claim that wall-clock newest was preserved

### Requirement: Privacy-safe sync observability
The system SHALL persist run phase, duration, counts, retries, typed outcomes, causal coverage, quarantine count, and hashed identifiers sufficient to audit the reliability objective. It SHALL NOT log cookies, formhash, payload values, user content, or raw account identifiers.

#### Scenario: User opens sync details after failure
- **WHEN** a sync run fails or pauses
- **THEN** the cloud-sync page shows durable phase, typed reason, pending/quarantine counts, last success, and next action without relying on a Snackbar

#### Scenario: Reliability report is computed
- **WHEN** rolling eligible-demand success is evaluated
- **THEN** excluded demands remain separately counted with explicit exclusion reasons

### Requirement: Manual authority overrides are previewed and stale-safe
The system SHALL expose force push and force pull only as explicit recovery actions. Before confirmation it SHALL load and validate the complete remote operation set and show a privacy-safe comparison grouped by sync module and add/update/delete or enable/disable action. Confirmation SHALL remain disabled for 10 seconds. The confirmation SHALL carry an opaque token over the compared local state and verified remote state, and the service SHALL reload and reject the operation when that token is stale.

#### Scenario: Force push makes local state authoritative
- **WHEN** the user confirms a current force-push preview after the delay
- **THEN** the system SHALL create causally later local operations for every semantic difference
- **AND** cloud-only live entities SHALL receive explicitly authorized delete operations
- **AND** the normal typed publication path SHALL acknowledge the exact submitted batch

#### Scenario: Force pull makes verified cloud state authoritative
- **WHEN** the user confirms a current force-pull preview after the delay
- **THEN** the system SHALL replace the local sync scope and discard unpublished local operations in one transaction
- **AND** the replacement SHALL use only the completely validated cloud reduction
- **AND** the next ordinary synchronization SHALL not resurrect discarded local state

#### Scenario: Cloud load or token validation fails
- **WHEN** discovery, parsing, validation, or preview-token revalidation fails
- **THEN** neither force action SHALL modify, clear, delete, or replace local sync data
- **AND** the UI SHALL require a new preview before confirmation

### Requirement: Sync status is human-readable and explainable
The cloud-sync page SHALL format epoch milliseconds as a human-readable date-time and SHALL expose privacy-safe summaries of the latest applied remote and acknowledged local operations. Each summary SHALL identify the sync module and counts for applicable actions including added, updated, deleted, enabled, and disabled, without exposing raw entity ids or user payload values.

#### Scenario: A mixed synchronization completes
- **WHEN** a run applies or acknowledges operations from multiple domains
- **THEN** sync details SHALL show one or more module/action summaries
- **AND** the last verified value SHALL be a formatted date-time rather than raw epoch milliseconds

### Requirement: Payload confidentiality is not claimed
The system SHALL describe JSON/Gzip/Base64 payload processing as serialization and compression, not encryption. Sync payloads SHALL exclude authentication secrets and use private blog visibility where supported.

#### Scenario: Journal is encoded
- **WHEN** a journal payload is compressed and Base64 encoded
- **THEN** UI, documentation, and code naming do not claim that its contents are encrypted
