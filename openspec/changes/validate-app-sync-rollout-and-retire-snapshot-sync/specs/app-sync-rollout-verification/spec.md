## ADDED Requirements

### Requirement: Eligible-demand rollout metric
The rollout verifier SHALL count at least 100 eligible sync demands, SHALL report numerator, denominator, retries, exclusions, and manual interventions, and SHALL require at least 99% fixed-point convergence.

#### Scenario: Eligible demand converges after retry
- **WHEN** a dirty or remote-change trigger has valid authentication and execution opportunity and reaches a fixed point after an automatic retry
- **THEN** the verifier counts one eligible successful demand rather than multiple attempts

#### Scenario: Ineligible demand is excluded
- **WHEN** authentication is expired or no required execution opportunity exists
- **THEN** the verifier records a typed exclusion and does not include it in the eligible denominator

### Requirement: Acknowledged-operation durability
The rollout verifier MUST observe zero known loss of acknowledged operations across retry, restart, duplicate delivery, reordering, checkpoint adoption, and force recovery.

#### Scenario: Restart after acknowledgement
- **WHEN** the app process and emulator restart after an operation is acknowledged
- **THEN** the operation remains represented by resolved state or a retained tombstone and does not re-enter pending state

### Requirement: Representative portable-domain coverage
The rollout SHALL cover local favorites, syncable settings, reading/history progress, FavoriteUpdate events, read/dismiss lifecycle markers, and FID/category filter choices, including representative additions and removals in both directions.

#### Scenario: Device A seeds representative local data
- **WHEN** Device A locally favorites ten 管理版 page-one threads, tag 21661, and the RSS `app` result, reads a representative subset, refreshes Updates, and changes settings
- **THEN** the corresponding stable operation domains are emitted without submitting any favorite action to the Yamibo website

#### Scenario: RSS subscription is portable without provider cache
- **WHEN** a device creates, renames, enables, disables, or deletes the RSS `app` subscription
- **THEN** `rss.search-subscription` operations use normalized query plus optional forum scope as identity and exclude result rows, page cache, refresh diagnostics, and local ids

#### Scenario: Device B changes lifecycle and filters
- **WHEN** Device B marks an update read or dismissed and changes an update filter
- **THEN** Device A converges to the lifecycle and filter winners without duplicate logical events

### Requirement: Fresh and default state safety
A fresh, reset-looking, or nearly default local database MUST adopt verified cloud state before publishing local migration operations and MUST NOT infer deletes from absence.

#### Scenario: Empty local state meets populated cloud
- **WHEN** an independent device with no portable records binds to a populated verified cloud
- **THEN** it pulls and materializes cloud state before any local migration publication and does not delete cloud entities

### Requirement: Data-preserving emulator execution
Emulator validation MUST install with `adb install -r -t`, MUST preserve login and database state, and MUST verify foreground, background, and process-restart behavior without package clear or uninstall.

#### Scenario: Upgrade and restart preserve data
- **WHEN** the current debug APK upgrades the existing package and the process and emulator restart
- **THEN** pre-existing semantic fingerprints and record counts remain intact except for explicitly exercised product mutations

### Requirement: Live transport and controlled evidence remain distinct
The final evidence SHALL report controlled runtime demands separately from live Yamibo transport canaries and SHALL not expose secrets, raw content, raw account identifiers, or cloud payloads.

#### Scenario: Live provider is unavailable
- **WHEN** live Yamibo transport cannot complete safely
- **THEN** the verifier records the provider limitation, preserves controlled evidence, and leaves snapshot-sync retirement blocked

### Requirement: Routine snapshot decision retirement gate
The abandoned routine cloud snapshot Merge/Overwrite decision implementation SHALL be removed only after every rollout invariant passes. Manual `BackupModels` export/restore and rollback readability MUST remain.

#### Scenario: Any invariant fails
- **WHEN** convergence is below 99%, acknowledged-operation loss is nonzero, two-device coverage is missing, or failed cloud load mutates local state
- **THEN** legacy retirement remains blocked and the failing condition is retained as typed evidence

#### Scenario: All invariants pass
- **WHEN** every rollout and durability gate passes
- **THEN** the implementation removes only the abandoned routine cloud snapshot decision path and reruns full backup/AppSync regression validation
