## 1. Provider Limits and Domain Inventory

- [x] 1.1 Measure Yamibo private-blog body size, practical blog count, user-space pagination, request rate behavior, and authoritative reload latency with redacted fixtures
- [x] 1.2 Inventory every current `BackupModels` field and map it to a domain id, stable entity id, operation kind, conflict policy, and deletion authority
- [x] 1.3 Define evidence-based absolute and percentage quarantine thresholds for each destructive domain
- [x] 1.4 Add architecture guards that keep the abandoned whole-snapshot automatic upload/download reconciler disabled

## 2. Operation and Causality Model

- [x] 2.1 Implement type-safe device id, device epoch, writer nonce, monotonic sequence, operation id, domain id, entity id, and account-binding models
- [x] 2.2 Implement versioned `SyncOperation` kinds and codecs without logging payload content
- [x] 2.3 Implement causal high-watermark comparison, concurrency detection, and deterministic operation-id tie ordering
- [x] 2.4 Add unit tests for clock skew, equal timestamps, causal successors, true concurrency, duplicate ids, and codec compatibility

## 3. Durable Local Storage

- [x] 3.1 Add SQLDelight schema and migration for installation/database generation, device epochs, sequence allocation, and durable run lease
- [x] 3.2 Add outbox, applied-operation, causal-vector, conflict-history, quarantine, checkpoint, acknowledgement, and reliability-run tables
- [x] 3.3 Implement typed stores with atomic domain mutation plus outbox append and atomic remote apply plus high-watermark advancement
- [x] 3.4 Add migration, transaction rollback, process-restart lease recovery, idempotency, and corrupted-metadata tests

## 4. Domain Registry and Local Mutation Routing

- [x] 4.1 Implement the mandatory sync-domain contract and registry coverage validator
- [x] 4.2 Assign stable generated sync ids to categories/collections and stable relation ids independent of display names/local row ids
- [x] 4.3 Implement per-key settings and per-field entity register policies with retained loser history
- [x] 4.4 Implement reading progress policy, favorite field policy, membership remove-wins policy, and entity tombstone/generation policy
- [x] 4.5 Route all syncable local commands through transactional operation creation, moving syncable settings behind a SQL-backed canonical adapter
- [x] 4.6 Add domain policy tests for rename, same-name containers, unrelated concurrent fields, add/remove races, delete/update races, and explicit recreation

## 5. Journal, Index, and Checkpoint Formats

- [x] 5.1 Define distinct fixed markers and collision-resistant private titles for config/index, device journals, and immutable checkpoints
- [x] 5.2 Implement versioned journal envelopes with account binding, writer identity, contiguous sequence ranges, operations, acknowledgements, heartbeat, and fingerprint
- [x] 5.3 Implement advisory index and immutable `BackupModels` checkpoint envelopes while naming JSON/Gzip/Base64 as serialization rather than encryption
- [x] 5.4 Add malformed marker, wrong account, sequence gap, unsupported schema, fingerprint mismatch, and round-trip tests

## 6. Yamibo Journal Transport and Discovery

- [x] 6.1 Adapt the retained typed blog CRUD/provider layer for config/index, per-device journal, and immutable checkpoint lifecycle operations
- [x] 6.2 Implement cached verified blog identities and 24-hour/error/repair-triggered full discovery with `fetchUserSpaceMyBlogs(userId = null)`
- [x] 6.3 Implement initial-pull-verified own-journal append, FormHash-authenticated typed POST success, response blog-id resolution, and exact submitted operation acknowledgement without post-GET
- [x] 6.4 Detect writer-nonce collisions and rotate the installation to a new device epoch before any conflicting publication
- [x] 6.5 Add request-parser and fake-provider tests for success prompts, `messageText` errors, form expiry, unknown POST result, stale index, missing cached ids, and same-install writer races

## 7. Pull, Reduce, Publish Engine

- [x] 7.1 Implement validated journal pulls after local high-watermarks and idempotent operation deduplication
- [x] 7.2 Implement deterministic reduction, transactional apply, conflict-history retention, and per-operation quarantine that does not block valid journals
- [x] 7.3 Implement serialized pull-reduce-publish cycles with durable later-trigger convergence and bounded retry
- [x] 7.4 Persist operation lifecycle states and acknowledge only exact submitted operations after typed POST success
- [x] 7.5 Add deterministic 2-5-device model/property tests with reordered, duplicated, dropped, delayed, and concurrent deliveries

## 8. Bootstrap and Destructive Safety

- [x] 8.1 Implement explicit unbound, bootstrapping, active, paused, quarantined, and rebootstrap-required installation states
- [x] 8.2 Detect fresh/reset/corrupt/database-generation-mismatch installations and enforce pull-only bootstrap
- [x] 8.3 Adopt a verified checkpoint plus later operations before converting pre-existing local user data into explicit migration operations
- [x] 8.4 Enforce the 90-day inactivity threshold, checkpoint adoption, and new device epoch before a returning device can publish
- [x] 8.5 Implement durable bulk-delete authorization and threshold quarantine before destructive apply or publication
- [x] 8.6 Add tests proving empty/default local state, logout, failed load, migration failure, and database recreation cannot create deletes or overwrite cloud data

## 9. Verified Checkpoint Compaction

- [x] 9.1 Implement deterministic immutable checkpoint creation with typed POST success and unique response blog-id verification
- [x] 9.2 Implement exact checkpoint/vector acknowledgements in each active device journal
- [x] 9.3 Implement pruning only after all devices active within 90 days acknowledge coverage, retaining tombstones and recovery checkpoints
- [x] 9.4 Implement typed storage-pressure pause when safe compaction cannot complete
- [x] 9.5 Add tests for competing candidates, failed checkpoint writes, missing acknowledgements, inactive-device return, tombstone resurrection, and interruption at every compaction phase

## 10. Runtime, Background Execution, and UI

- [x] 10.1 Wire foreground/manual and automatic triggers through one app-scoped runtime and SQLDelight-backed lease
- [x] 10.2 Reuse existing Android WorkManager and iOS background entry points for durable pending runs without placing merge policy in platform workers
- [x] 10.3 Implement auth pause, bounded exponential retry with jitter, coalesced triggers, and process-restart recovery
- [x] 10.4 Connect the retained cloud-sync UI to durable bootstrap/pending/quarantine/last-success/next-action status without success/failure Snackbars
- [x] 10.5 Keep manual restore as a separately confirmed `BackupModels` recovery path and remove Merge/Overwrite choices from routine automatic sync
- [x] 10.6 Add runtime, scheduler, expired-FormHash, navigation/status-detail, process-death, and dark/light theme tests

## 11. Reliability Verification and Rollout

- [x] 11.1 Implement privacy-safe eligible-demand, exclusion, fixed-point, retry, quarantine, and manual-intervention telemetry
- [x] 11.2 Add failure-injection suites for provider outage, timeout-after-POST, rate limit, corrupt journal, stale index, account change, unsupported schema, and unknown exceptions
- [x] 11.3 Run focused shared/common/Android tests and SQLDelight migration tests, reporting exact suite boundaries and unrelated failures
- [x] 11.4 Run emulator tests across database reset, app restart, foreground/background overlap, two-device fixtures, upload verification, and cloud-load failure without clearing app data between runs
- [x] 11.5 Enable the new engine behind a disabled-by-default feature flag and verify at least 99% eligible-demand convergence with zero known acknowledged-operation loss
- [x] 11.6 Remove the abandoned routine snapshot Merge/Overwrite decision implementation only after rollout evidence meets the target; preserve manual export/restore and rollback readability

## 12. Manual Authority Overrides and Explainable Status

- [x] 12.1 Add force-push and force-pull previews that compare verified cloud and local resolved state by domain and action
- [x] 12.2 Require a 10-second themed confirmation dialog and revalidate an opaque preview token immediately before either override
- [x] 12.3 Implement force push as an explicit local-authoritative operation batch, including authorized deletes for cloud-only entities
- [x] 12.4 Implement force pull as a transactional verified-cloud replacement that discards unpublished local operations and never mutates local data after a failed cloud load
- [x] 12.5 Format verified epoch milliseconds as a human-readable date-time in the cloud-sync UI
- [x] 12.6 Report privacy-safe inbound and outbound change summaries by module and action such as added, updated, deleted, enabled, and disabled
- [x] 12.7 Add focused engine/controller/Compose tests for stale previews, countdown gating, rollback on failure, timestamp formatting, and detailed summaries
