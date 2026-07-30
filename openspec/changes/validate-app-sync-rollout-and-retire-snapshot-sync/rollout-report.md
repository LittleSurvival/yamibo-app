# AppSync Operation-Log Rollout Report

Generated: 2026-07-30 (Asia/Taipei)

## Decision

The bounded retirement gate passes for this branch:

- Controlled eligible demands: 100
- Controlled fixed-point convergence: 100
- Controlled convergence rate: 100.00%
- Retried demands: 10
- Controlled acknowledged-operation loss: 0
- Live unexplained acknowledged-operation loss: 0
- Live final pending operations: 0 on both replicas
- Live final quarantine entries: 0 on both replicas

This result satisfies the OpenSpec threshold for removing an abandoned routine snapshot
Merge/Overwrite implementation. It is evidence for this bounded matrix, not a universal
claim that every future network, provider, database, or device condition will converge
with 99% probability.

## Controlled Evidence

`OperationSyncEngineTest.oneHundredEligibleDemandsConvergeWithinTwoWindowsWithoutAckLoss`
executes the real reducer/engine/store boundary with an in-memory authoritative provider:

| Measure | Result |
| --- | ---: |
| Eligible demands | 100 |
| Converged demands | 100 |
| Retry windows | 10 |
| Unique acknowledged operations | 100 |
| Acknowledged-operation loss | 0 |
| Exclusions in the eligible denominator | 0 |

The matrix rotates through settings, favorite items, RSS search subscriptions, thread
reading progress, FavoriteUpdate events, FID choices, and category choices. Every tenth
demand uses an accepted POST followed by an unknown response and verifies retry with the
same operation id. Separate evidence-model tests verify that typed auth exclusions are
reported outside the eligible denominator and that one acknowledged loss or a result
below 99% blocks retirement.

Additional deterministic suites cover two-to-five replicas, duplicate and reordered
delivery, concurrent changes, old Put replay after tombstones, fresh/reset bootstrap,
90-day inactive-device rebootstrap, process interruption, failed authoritative load,
stale force confirmation, checkpoint adoption, and foreground/background lease overlap.

## Live Setup

Two Android virtual devices used independent application databases and the same configured
cloud account:

| Replica | AVD label | DB inode | DB schema |
| --- | --- | ---: | ---: |
| Device A | Pixel phone | 362130 | 35 |
| Device B | Pixel tablet | 361653 | 35 |

Every APK update used `adb install -r -t`. The package was never cleared, uninstalled, or
downgraded during the rollout. The database inodes remained unchanged across installs.
Process restart and an emulator reboot preserved the seeded data and authenticated state.
No cookie, FormHash, account identifier, blog id, raw content, or database file is included
in this report.

## Live Product Canaries

Device A was seeded through product UI with ten local-only thread favorites from the
requested Home section, one tag favorite for the requested tag URL, and one RSS search
subscription for `app`. These were local application favorites only; no Yamibo website
favorite submission was invoked.

The rollout then exercised:

- Initial Device A additions to Device B without force overwrite
- Fresh/default-looking Device B adoption of verified cloud state before local migration
- Representative reading of favorites to create history/progress
- Updates global refresh producing seven durable FavoriteUpdate events
- Read and dismiss lifecycle changes in both directions
- FID filter disablement and representative syncable setting changes
- Deletion of one history record and one local RSS favorite/subscription
- Foreground manual sync on both replicas
- Android WorkManager execution on both replicas, including a post-restart worker run
- Normal cached-index checkpoint loading after one explicit recovery discovery

Final materialized counts on both replicas:

| Projection | Device A | Device B |
| --- | ---: | ---: |
| Local favorites | 11 | 11 |
| Thread histories | 12 | 12 |
| Visible update events | 6 | 6 |
| Read update events | 1 | 1 |
| Dismissed update events | 1 | 1 |
| Resolved entities | 98 | 98 |

Privacy-safe final fingerprints:

| Projection | Device A | Device B |
| --- | --- | --- |
| Resolved operation state | `457cf10afc7e99dc` | `457cf10afc7e99dc` |
| Favorites | `457dbdb42bb6c621` | `457dbdb42bb6c621` |
| Reading history (REAL values normalized to 8 decimals) | `50805e39dba562b3` | `50805e39dba562b3` |
| FavoriteUpdate records | `4e5413785229e7e7` | `4e5413785229e7e7` |
| Canonical settings | `8edefebbaa08c7ec` | `8edefebbaa08c7ec` |

The final ordinary sync on each replica reported convergence with zero pending and zero
quarantine. Device B loaded nine checkpoint references through the normal cached-index
path; Device A then pulled Device B's final reading-progress operation and reached the
same resolved-state fingerprint.

## Incidents And Fixes

The live canary intentionally continued through non-account-destructive failures:

1. A duplicate local favorite recording path generated an operation storm. The command
   boundary was made idempotent and old epoch operations were excluded from publication.
2. Bootstrap without a checkpoint retained stale resolved state. Bootstrap now constructs
   an authoritative cloud base before admitting non-conflicting local migration drafts.
3. Raw journal and checkpoint payloads exceeded the provider's safe body limit. Schema 2
   uses bounded Gzip/Base64 envelopes while retaining schema 1 read compatibility and
   decompression limits.
4. A paused-provider refresh path incorrectly entered destructive recovery and temporarily
   replaced local projections. The preserved pre-action database was restored immediately;
   refresh state routing was fixed and tests now prohibit implicit force pull.
5. Fieldless remote tombstones caused materializer exceptions for several stable-identity
   domains. Delete materialization now derives identity from the entity key and is covered
   by regression tests.
6. Active replicas acknowledged checkpoints without adopting their canonical resolved
   state. Verified checkpoint reconciliation now adopts checkpoint plus uncovered journal
   operations transactionally before acknowledgement.
7. The cached/index fast path loaded journals but ignored checkpoint references. It now
   verifies and returns both; a regression test proves checkpoint loading without full
   discovery. The two-device live fixed point above verifies the repair.
8. Real provider eventual consistency caused transient delete/reload and POST verification
   retries. The engine retained pending work and later converged without changing operation
   identity.
9. A System UI ANR and loading skeleton caused false navigation/no-sync observations. These
   were separated from AppSync results by DB reliability rows and subsequent product UI
   verification.

No Yamibo website favorite was added or removed by the rollout. No final acknowledged
winner or tombstone was missing.

## Retirement Audit

The current branch contains no routine cloud snapshot upload/download Merge/Overwrite
decision implementation to delete. Routine sync is operation-log based. The remaining
`BackupRepository.RestoreMode.Merge` and `RestoreMode.Overwrite` code is the explicitly
preserved manual local backup/restore path. `BackupModels` checkpoints and legacy envelope
readers remain for bootstrap, rollback readability, and recovery.

## Validation Gate

The final commit is gated on:

- PASS: focused AppSync, backup, checkpoint, materializer, remote, summary, and UI-state tests
- PASS: complete `:shared:testDebugUnitTest`
- PASS: complete `:composeApp:testDebugUnitTest`
- PASS: iOS simulator Kotlin compilation for shared and Compose modules
- PASS: Android debug APK assembly
- PASS: strict validation of all active changes and `git diff --check`
