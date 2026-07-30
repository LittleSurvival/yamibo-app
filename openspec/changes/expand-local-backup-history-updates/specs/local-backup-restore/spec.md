## ADDED Requirements

### Requirement: Complete declared local backup scope
The system SHALL export all declared user-owned local backup domains: favorites and relationships, RSS search subscriptions, exportable settings, notes, bookmarks, thread history, image history, tag-manga history, tag-catalog history, RSS-search history, RSS-catalog history, chapter read/progress state, reading-time statistics, favorite-update events, and favorite-update filter enablement.

RSS subscription export SHALL use normalized query plus optional forum scope as stable identity, SHALL exclude search results/page caches and refresh diagnostics, and restore SHALL remap RSS reading-history source ids to the restored local subscription ids.

#### Scenario: Existing reading datasets are exported
- **WHEN** a local backup is created from a database containing thread, image, tag-manga, and reading-time records
- **THEN** every field already represented by `BackupReadingState` is present in the backup
- **AND** the coverage audit does not describe or treat those datasets as newly introduced

#### Scenario: Missing history families are exported
- **WHEN** tag-catalog, RSS-search, RSS-catalog, or chapter progress records exist
- **THEN** each record and all user-progress fields are represented in the backup

#### Scenario: Update records are exported
- **WHEN** favorite-update events or user-disabled fid/category filters exist
- **THEN** all durable event fields, canonical `syncId`/`sourceFingerprint` when available, read/dismissed timestamps, and user-selected enablement are represented

### Requirement: Explicit durable and ephemeral data boundary
The local backup manifest SHALL classify every relevant SQL/settings domain as included or excluded with a reason. It SHALL exclude authentication, cookies, formhash, remote sync metadata, favorite-update tracked-target baselines and fingerprints, update-run progress/logs, scheduler state, transient errors, derived filter labels/counts, caches, download files, and platform paths.

#### Scenario: Runtime update state is present
- **WHEN** a backup is created while a favorite-update scan has tracked targets, a recoverable run, logs, or errors
- **THEN** those runtime records are not serialized
- **AND** durable update events and user filter enablement remain serialized

#### Scenario: New portable table is introduced
- **WHEN** a future SQL/settings domain is added within the audited data area
- **THEN** the coverage contract fails until the domain has an explicit scope declaration and matching adapter coverage or an exclusion reason

### Requirement: Backward-compatible additive decoding
The system SHALL decode existing schema-1 backup files that omit the new history/update sections by treating each omitted section as empty, SHALL continue ignoring unknown JSON fields, and SHALL continue rejecting unsupported future schema versions before mutation.

#### Scenario: Existing schema-1 fixture is restored
- **WHEN** a backup produced before this change is loaded
- **THEN** its existing favorites, settings, notes, bookmarks, reading histories, and reading-time statistics restore under the current behavior
- **AND** absent new sections do not cause a parse or validation failure

#### Scenario: New additive file is read by an older decoder
- **WHEN** an older schema-1 decoder configured to ignore unknown fields reads an expanded backup
- **THEN** known fields remain decodable without requiring an AppSync checkpoint schema migration

#### Scenario: Unsupported schema is selected
- **WHEN** the file declares a schema version newer than the supported backup schema
- **THEN** restore fails before any local data or setting is changed

### Requirement: Preflight validation before destructive restore
The system SHALL completely read, decode, migrate, and validate the selected backup and construct its restore plan before clearing or mutating any restorable data. Validation SHALL cover storage enum values, duplicate identities, relationship references, numeric bounds, and configured collection-size limits without silently truncating records.

#### Scenario: Malformed overwrite file is loaded
- **WHEN** Overwrite is selected and decoding or validation fails
- **THEN** favorites, settings, histories, chapter progress, update events, and filter preferences remain unchanged

#### Scenario: Required relationship cannot be resolved
- **WHEN** a required favorite relationship references no category or item in the validated restore plan
- **THEN** restore fails before mutation with an actionable error

#### Scenario: Auxiliary category filter is orphaned
- **WHEN** an update category filter cannot resolve a restored/current category `syncId`
- **THEN** the restore plan marks that auxiliary filter as skipped with an explicit warning and count
- **AND** the otherwise valid restore may proceed
- **AND** the raw source category id is never applied

#### Scenario: Payload exceeds a bound
- **WHEN** a history or update-event collection exceeds its configured safe limit
- **THEN** restore fails before mutation
- **AND** the system does not truncate and report success

### Requirement: Atomic restore and rollback
The system SHALL apply all SQL-owned backup domains in one transaction. Overwrite SHALL clear only the declared restorable scope inside that transaction. Preference-backed settings SHALL use rollback-capable staging or a pre-apply snapshot so any failed restore leaves both SQL data and settings at their pre-restore values.

#### Scenario: Failure follows overwrite clearing
- **WHEN** an exception is injected after one or more included tables are cleared or written
- **THEN** the transaction rolls back every SQL mutation
- **AND** all preference-backed settings retain their pre-restore values

#### Scenario: Cloud or file load fails
- **WHEN** reading the selected source fails before a validated restore plan exists
- **THEN** no clear, merge, setting write, or AppSync mutation occurs

### Requirement: Deterministic Merge behavior
Merge restore SHALL upsert records by their declared stable identity and SHALL prevent older imported reading progress from replacing newer local progress. Favorite-update events SHALL reuse the cloud-sync canonical `sourceFingerprint`/`syncId` contract, with later non-null read/dismissed timestamps retained.

#### Scenario: Imported history is older
- **WHEN** local and imported history/progress share an identity and the local `lastVisitTime` or `updatedAt` is later
- **THEN** Merge retains the local record

#### Scenario: Imported history is newer
- **WHEN** local and imported history/progress share an identity and the imported `lastVisitTime` or `updatedAt` is later
- **THEN** Merge applies the imported record

#### Scenario: Same update event exists on both sides
- **WHEN** local and imported events have the same canonical `syncId` derived from target, mode, and immutable upstream evidence
- **THEN** Merge creates only one event
- **AND** its `readAt` and `dismissedAt` retain the later non-null timestamp from either side

#### Scenario: Display content or detection time differs
- **WHEN** two normal events contain the same canonical target, mode, and immutable upstream evidence but different title, summary, or `detectedAt`
- **THEN** they resolve to the same `sourceFingerprint` and `syncId`

#### Scenario: Legacy ambiguous event lacks immutable evidence
- **WHEN** an imported legacy ambiguous event has no immutable upstream detail evidence and no stored stable identity
- **THEN** restore derives its identity from deterministic retained observation content and retained `detectedAt`
- **AND** it does not merge the event into an unrelated observation

### Requirement: Overwrite replaces the complete declared scope
After successful validation, Overwrite SHALL replace every included local-backup SQL domain, including the four newly covered reading/progress datasets and durable favorite-update state, while leaving excluded runtime/cache domains outside the restore contract.

#### Scenario: Valid overwrite is confirmed
- **WHEN** a valid expanded backup is restored with Overwrite
- **THEN** stale local records in every included domain are removed
- **AND** the backup records are restored atomically
- **AND** tracked-target, run, scheduler, and cache state are not imported from the file

### Requirement: Portable update-filter restoration
The system SHALL restore fid filter enablement by stable fid and category filter enablement through category `syncId`, never a source database category id. It SHALL rebuild derived filter names, counts, and timestamps from current restored favorites. An unresolved category filter SHALL be skipped as auxiliary state with an explicit warning/count and SHALL NOT fail an otherwise valid restore.

#### Scenario: Category database ids differ
- **WHEN** restored favorite categories receive different local database ids
- **THEN** each category filter is applied to the category resolved from its category `syncId`
- **AND** no filter is applied using the raw source database id

#### Scenario: Category filter is orphaned
- **WHEN** a backed-up category filter's `syncId` cannot resolve after favorite restoration
- **THEN** that filter is skipped
- **AND** the restore result increments its skipped-orphan count and exposes a warning
- **AND** all other valid backup data remains eligible to commit

#### Scenario: Derived filter metadata differs
- **WHEN** imported filter labels or counts differ from the restored favorite graph
- **THEN** the system recomputes labels and counts from current favorites
- **AND** only the user's enabled/disabled choice is preserved

### Requirement: AppSync scope remains independent
Snapshot/checkpoint readers SHALL select data through an explicit manifest scope. FavoriteUpdate records SHALL remain excluded from AppSync until the separate cloud capability registers their domains. After registration, AppSync SHALL include the same shared FavoriteUpdate projection exactly once and SHALL NOT create a duplicate checkpoint section, transport model, or mapper.

#### Scenario: Checkpoint is created before cloud registration
- **WHEN** local FavoriteUpdate records exist but their domains are absent from the AppSync manifest
- **THEN** the checkpoint excludes those records
- **AND** existing AppSync domains remain unchanged

#### Scenario: Checkpoint is created after cloud registration
- **WHEN** the separate cloud capability registers FavoriteUpdate domains in the AppSync manifest
- **THEN** the checkpoint includes the shared FavoriteUpdate projection exactly once
- **AND** checkpoint validation uses that same projection with no parallel backup-to-cloud transport

### Requirement: Accurate backup UI and restore reporting
The existing local backup page SHALL concisely communicate that backups include settings, favorites, reading/history state, and update records. A successful restore SHALL report separate counts for favorites, settings, reading/history records, and favorite-update events without adding a new restore mode.

#### Scenario: Expanded backup page is opened
- **WHEN** the user views the local backup page
- **THEN** its title, subtitle, or concise scope text does not imply that only settings and favorites are protected

#### Scenario: Expanded restore succeeds
- **WHEN** a backup containing new history families and update events is restored
- **THEN** the success feedback includes their counts in the appropriate history and update categories
