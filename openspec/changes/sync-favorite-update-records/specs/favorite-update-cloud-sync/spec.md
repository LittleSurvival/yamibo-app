## ADDED Requirements

### Requirement: FavoriteUpdate durable sync scope
The system SHALL synchronize FavoriteUpdate events, their read and dismissed state, and explicit FID/category filter enabled choices. It SHALL NOT synchronize run status, phase, progress, log, warning, error, tracked-target baseline, check timestamp, provider fingerprint, retry metadata, locally derived labels, or item counts.

#### Scenario: Update run reports progress
- **WHEN** a FavoriteUpdate scan changes run progress, log, warning, error, or tracked-target metadata
- **THEN** the system stores that state locally without appending a cloud operation

#### Scenario: User changes a durable state
- **WHEN** a user marks an event read, dismisses an event, or toggles an update filter
- **THEN** the system appends a durable operation atomically with the local mutation

### Requirement: Stable FavoriteUpdate event identity
The system SHALL identify an update event by a deterministic stable fingerprint of immutable target, mode, and upstream source evidence, independent of local database id, title, summary, or device timestamp. Event detection SHALL be idempotent for the same stable identity.

#### Scenario: Two devices detect the same posts
- **WHEN** two devices independently detect an update with the same target, mode, and immutable post/thread ids
- **THEN** both produce the same event entity identity and converge to one logical event

#### Scenario: Two updates occur for one target
- **WHEN** the same favorite target produces different immutable source evidence in separate scans
- **THEN** the system creates separate stable event entities

#### Scenario: Legacy ambiguous event is migrated
- **WHEN** an existing ambiguous event has no immutable detail ids
- **THEN** migration assigns a deterministic identity using its retained observation content without merging it into an unrelated event

### Requirement: Event operation and conflict semantics
The `favorite.update-event` domain SHALL use a full `Put` for event creation, non-null `Patch` fields for `readAt` and `dismissedAt`, and an explicit remove-wins `Delete` tombstone for physical purge. Its LWW register SHALL order causally observed successors first and SHALL use deterministic operation-id ordering only for truly concurrent scalar values. Device wall-clock timestamps SHALL NOT determine the winner. Losing values SHALL remain available to conflict history. Missing local or snapshot rows SHALL mean no opinion.

#### Scenario: Read races with event creation
- **WHEN** a read patch is concurrent with another device's put for the same event
- **THEN** the resolved event remains present and read because the put does not write a null `readAt`

#### Scenario: Dismissed event reaches another device
- **WHEN** a dismiss patch is synchronized
- **THEN** the receiving device retains the event record with `dismissedAt` and excludes it from the active list

#### Scenario: Old event put arrives after purge
- **WHEN** an old put is replayed after an observed event tombstone
- **THEN** the event remains deleted and the losing operation remains in conflict history

#### Scenario: Device clocks disagree
- **WHEN** a causally older operation contains a later wall-clock timestamp than its causal successor
- **THEN** the causal successor wins and every replica resolves the same event state

#### Scenario: Concurrent event fields disagree
- **WHEN** two valid event operations concurrently write different mutable display values for the same stable identity
- **THEN** every replica selects the same deterministic operation-id winner without changing the event identity

#### Scenario: Local database is reset
- **WHEN** local FavoriteUpdate event rows are absent after reset or corruption
- **THEN** the system creates no event delete operations

### Requirement: Stable and minimal filter synchronization
The system SHALL synchronize only the durable `enabled` choice for each FID and favorite category filter. FID identity SHALL use the numeric FID, category identity SHALL use `LocalFavoriteCategory.syncId`, and local category ids SHALL NOT appear in cloud identity or payload.

#### Scenario: Category ids differ across devices
- **WHEN** two devices materialize the same category under different local ids
- **THEN** the update-filter choice resolves through the shared category sync id

#### Scenario: Derived filter metadata changes
- **WHEN** a refresh changes forum/category name or item count
- **THEN** no sync operation is emitted for those derived fields

#### Scenario: Filter temporarily disappears
- **WHEN** refresh removes a derived filter row because no current favorite references it
- **THEN** its durable enabled choice remains stored and no tombstone is emitted

#### Scenario: Devices toggle concurrently
- **WHEN** devices concurrently choose different enabled values for one filter
- **THEN** all devices choose the same operation-id winner and retain the conflict record

#### Scenario: Later filter choice observes earlier choice
- **WHEN** a filter toggle operation causally observes an earlier toggle
- **THEN** the observed successor wins regardless of either device's wall-clock time

### Requirement: Transactional repository mutation recording
FavoriteUpdate event creation, read, dismiss, batch dismiss, dismiss-all, and filter toggle methods SHALL commit their domain mutation and operation outbox entries atomically when AppSync is recordable. Repeated event detection or command retry SHALL NOT create duplicate logical operations.

#### Scenario: Process stops during event creation
- **WHEN** the process stops before the event creation transaction commits
- **THEN** neither the event row nor its operation is visible after restart

#### Scenario: Batch dismiss fails
- **WHEN** one mutation in a batch dismiss transaction fails
- **THEN** none of the event dismiss changes or corresponding operations commit

#### Scenario: Same event is detected again
- **WHEN** the detector observes an existing stable event identity
- **THEN** it does not insert a duplicate event or append a duplicate creation operation

### Requirement: FavoriteUpdate checkpoint and bootstrap coverage
Verified checkpoints and backup-based migration projection SHALL include FavoriteUpdate events, lifecycle markers, FID choices, and category choices while excluding local-only run/cache state. Bootstrap SHALL adopt verified cloud state before admitting local migration drafts and SHALL reject local drafts whose domain/entity identity already exists in cloud state.

#### Scenario: New device bootstraps
- **WHEN** a new or reset device has empty FavoriteUpdate tables and cloud contains events and filter choices
- **THEN** bootstrap materializes the cloud records before the device can publish local defaults

#### Scenario: Existing device first enables sync
- **WHEN** a device has local FavoriteUpdate events or filter choices absent from verified cloud state
- **THEN** bootstrap converts them to migration operations after cloud adoption

#### Scenario: Cloud load fails
- **WHEN** checkpoint or journal loading fails validation or transport
- **THEN** no FavoriteUpdate event, lifecycle marker, filter choice, run row, or tracked-target row is changed or deleted

#### Scenario: Checkpoint is reloaded
- **WHEN** a checkpoint containing FavoriteUpdate domains is authoritatively reloaded
- **THEN** its backup projection, resolved entities, tombstones, and causal coverage validate consistently

### Requirement: Transactional FavoriteUpdate materialization
The materializer SHALL apply FavoriteUpdate events and durable choices in the same transaction as applied-operation markers and causal watermarks. It SHALL preserve local event ids for UI lookup, resolve category choices by category sync id, and roll back all domain effects on failure.

#### Scenario: Event patch is applied
- **WHEN** a valid remote read or dismiss patch wins reduction
- **THEN** the existing local event row identified by sync id is updated without changing its local id

#### Scenario: Category is not yet available
- **WHEN** a category filter choice arrives before its category entity can be resolved
- **THEN** the choice remains durable and is projected after that category sync id becomes available

#### Scenario: Materialization fails
- **WHEN** any FavoriteUpdate event or choice cannot be materialized
- **THEN** its domain changes, applied markers, and high-watermarks all remain at their pre-transaction values

#### Scenario: Force pull clears syncable state
- **WHEN** a confirmed force pull replaces FavoriteUpdate cloud-syncable state
- **THEN** events and durable choices follow cloud state while run and tracked-target tables remain local

### Requirement: FavoriteUpdate force comparison and summaries
Normal sync, expandable sync details, and manual force previews SHALL semantically compare and summarize FavoriteUpdate domains without using local database ids as identity. User-facing results SHALL distinguish event added, updated, read, dismissed, or deleted changes and filter enabled or disabled changes. Each module/action group SHALL contain the total count and up to five human-readable item details, followed by the undisplayed count when more items changed. Event details SHALL identify title and target/forum context when available. Filter details SHALL use a locally resolved label when available and a non-sensitive stable fallback otherwise. Summary generation failure SHALL NOT fail synchronization.

#### Scenario: Force preview compares devices
- **WHEN** local and cloud contain different FavoriteUpdate events or lifecycle markers
- **THEN** the preview reports grouped local/cloud differences for Favorite updates before confirmation

#### Scenario: Filter changes converge
- **WHEN** synchronization applies filter choice winners
- **THEN** the result identifies the affected FID or category-filter module, enabled or disabled action, total count, and bounded affected-filter details

#### Scenario: Several update records change
- **WHEN** synchronization adds, reads, dismisses, updates, or deletes FavoriteUpdate events
- **THEN** sync details group the results by module and action and identify up to five affected titles or targets plus the remaining count

#### Scenario: Detail formatting fails
- **WHEN** an event or filter lacks display metadata or one detail cannot be formatted
- **THEN** synchronization succeeds and the UI still reports the correct module, action, count, and safe fallback identity

#### Scenario: Preview becomes stale
- **WHEN** a FavoriteUpdate event or choice changes after preview but before force confirmation
- **THEN** the stale token is rejected and no force mutation occurs

### Requirement: FavoriteUpdate migration safety
Database migration SHALL deterministically backfill event stable identities, preserve event content and lifecycle markers, and copy filter choices using FID or category sync identity. It SHALL skip unresolved orphan category projections rather than upload a local category id, and it SHALL be idempotent.

#### Scenario: Existing records migrate
- **WHEN** the database contains pre-sync FavoriteUpdate events and disabled filters
- **THEN** migration preserves their user-visible and enabled state with stable cloud identities

#### Scenario: Category lacks a sync id
- **WHEN** a category filter row cannot resolve a stable category sync id
- **THEN** it remains local and no invalid cloud operation is created

#### Scenario: Migration runs again
- **WHEN** initialization retries after the schema migration completed
- **THEN** stable identities and migration operation planning remain unchanged and no duplicate logical event is created

### Requirement: FavoriteUpdate cross-device rollout verification
The change SHALL NOT be considered rollout-ready until automated and data-preserving device validation demonstrates convergence of FavoriteUpdate events, lifecycle markers, and filter choices under normal, duplicate, reordered, interrupted, and restart flows. Validation SHALL include empty/reset-device bootstrap and failed-cloud-load safety, and SHALL record any quarantine, retry, projection divergence, or acknowledged-operation loss.

#### Scenario: Two devices synchronize both directions
- **WHEN** one device creates an update event and the devices alternately change read, dismissed, FID-filter, and category-filter state
- **THEN** both devices converge to identical stable event identities, lifecycle state, and durable filter choices without force override

#### Scenario: Device restarts during rollout validation
- **WHEN** an app process or emulator restarts after operations are committed locally but before all peers apply them
- **THEN** retry resumes idempotently and converges without duplicate events or lost lifecycle/filter changes

#### Scenario: Reset device joins existing cloud state
- **WHEN** an empty or reset device joins a cloud state containing FavoriteUpdate records
- **THEN** it adopts cloud records before publishing migration/default state and creates no absence-derived tombstones

#### Scenario: Rollout records unexplained loss
- **WHEN** validation observes acknowledged-operation loss, unexplained final projection divergence, or a failed cloud load mutating local FavoriteUpdate data
- **THEN** rollout readiness remains blocked until the cause is fixed and the affected validation is rerun
