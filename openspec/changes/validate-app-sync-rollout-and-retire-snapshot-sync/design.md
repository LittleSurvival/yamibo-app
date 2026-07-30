## Context

`redesign-operation-log-cloud-sync` task 11.6 intentionally remains open because deleting the routine snapshot decision path is irreversible without rollout evidence. The engine already has deterministic 100-demand tests and earlier emulator coverage, but this follow-up must validate the current branch after FavoriteUpdate registration and must distinguish controlled runtime evidence from live Yamibo transport canaries.

The test account and cloud blogs are real. Tests may create/update/delete the private AppSync blogs through existing APIs. They MUST NOT invoke Yamibo website favorite synchronization: requested thread/tag favorites are local `LocalFavorite*` records and the RSS favorite is a local `RssSearchSubscription`. APK installation MUST use `adb install -r -t`; package clear, uninstall, downgrade, and destructive database replacement are forbidden.

## Goals / Non-Goals

**Goals:**

- Produce reproducible evidence for at least 100 eligible sync demands with at least 99% fixed-point convergence and zero known acknowledged-operation loss.
- Cover real favorites, settings, history/progress, FavoriteUpdate events/lifecycle/filter choices, add/remove behavior, process restart, background execution, and two-device convergence.
- Prove a fresh/default-looking or reset local state cannot publish absence/defaults over verified cloud data.
- Remove only the abandoned routine cloud snapshot Merge/Overwrite decision implementation after the gate passes.
- Preserve manual local backup export/restore, rollback-readable models, journals, checkpoints, and operation tables.

**Non-Goals:**

- Synchronizing local favorites to Yamibo's website favorite feature.
- Treating timestamps as conflict authority.
- Claiming statistical confidence beyond the measured sample or claiming provider availability from controlled tests.
- Deleting cloud blogs, app data, or user records merely to simplify fixtures.

## Decisions

### 1. Use a two-tier evidence set

Tier A runs at least 100 eligible demands through the app runtime/engine on emulators with deterministic provider fault injection. Tier B runs live Yamibo transport canaries for every operation family and both directions. The final report publishes Tier A and Tier B separately and never substitutes controlled evidence for raw provider availability.

Alternative: perform 100 live blog writes. Rejected because it creates unnecessary provider load and makes a reliability result depend on abusive test traffic.

### 2. Count eligible demands, not button presses

One demand begins when a dirty-local or verified-remote-change trigger is durably accepted. It is eligible only when cached authentication/FormHash are valid and an execution opportunity exists. Success requires a fixed point, no pending non-quarantined operation, all valid fetched operations applied, and no manual conflict choice. Retries remain part of the same demand.

Excluded demands and reasons are reported separately. Authentication expiry, deliberate network denial, unsupported schema quarantine, and unavailable OS execution opportunity do not enter the eligible denominator.

### 3. Preserve and fingerprint emulator state around tests

Before testing, record package version, database schema/user version, table counts, selected semantic fingerprints, and login availability. Install with `-r -t`, restart repeatedly, and compare after every phase. Raw content, cookies, FormHash, blog payloads, and account ids are not copied into evidence.

### 4. Seed through product workflows, inspect through read-only SQL

Device A creates local test data through UI workflows:

- locally favorite the first ten threads from Home > 管理版 page 1;
- locally favorite tag `https://bbs.yamibo.com/misc.php?mod=tag&id=21661`;
- locally favorite the RSS search result for query `app`;
- open a representative subset to create history/progress;
- run Updates global refresh;
- change representative syncable settings.

Read-only `run-as` database inspection verifies identities/counts. Test cleanup uses product deletion commands so tombstones are emitted. Direct SQL is allowed only for read-only evidence or isolated instrumentation fixtures, never to manufacture a live pass.

The rollout SHALL treat `RssSearchSubscription` as its own `rss.search-subscription`
remove-wins domain. Its stable identity is normalized query plus optional forum
scope. Search-result rows, page caches, refresh status/messages, and local
autoincrement ids remain device-local and never enter operation envelopes.

### 5. Exercise add and remove in both directions

Device A adds and later removes representative favorite/history/update/filter state. Device B independently changes read/dismiss/filter/settings state. Each direction must converge after reordered/duplicate delivery and restart. Event dismissal is a lifecycle patch; only an explicit physical purge may create a Delete tombstone.

### 6. Gate legacy removal on all invariants

Removal is allowed only when:

- eligible convergence is at least 99%;
- acknowledged-operation loss is zero;
- both live-device directions pass;
- failed authoritative cloud loads produce zero local mutation;
- fresh/default local state adopts cloud before migration publication;
- restart/background tests preserve database content;
- manual backup Merge/Overwrite still passes.

If any invariant fails, task 11.6 and legacy removal remain open. The failure becomes a typed test record and implementation is fixed before rerun.

## Risks / Trade-offs

- [Only one connected emulator is initially available] -> Start a second compatible AVD or use a separately preserved emulator snapshot; do not claim two-device coverage until both independent databases are observed.
- [Live Yamibo transport is rate-limited or unavailable] -> Stop repeated writes, classify provider failures accurately, keep Tier A evidence, and leave retirement blocked.
- [UI automation is brittle] -> Use accessibility-visible selectors, screenshots, and read-only database fingerprints; retry navigation without repeating successful mutations.
- [Server-side favorite actions could be triggered accidentally] -> Use only the app's local favorite controls and stop immediately if a flow presents Yamibo website favorite submission.
- [A 100-demand point estimate has limited confidence] -> Report numerator, denominator, exclusions, and confidence limitation; do not market it as a universal guarantee.
- [Old rollback build cannot read schema 35] -> Rollback is feature disablement on the compatible build; never downgrade the database.

## Migration Plan

1. Capture pre-install emulator evidence and APK/database versions.
2. Install the current APK with `adb install -r -t` and prove data survives restart.
3. Seed Device A workflows, adopt verified cloud state, and run Tier A/Tier B demand matrices.
4. Establish Device B with an independent database/account binding and verify bidirectional convergence.
5. Exercise additions/removals, read/dismiss/filter/settings races, failed cloud load, background execution, restart, and force recovery.
6. Publish bounded evidence and compute the measured convergence ratio.
7. Only after the gate passes, remove the abandoned routine cloud snapshot decision implementation and rerun all regression suites.
8. Rollback by disabling scheduling/publication while retaining operation/outbox/checkpoint/blog state; never delete or reinterpret journals.

## Open Questions

No product decision is blocking execution. If a second AVD cannot be started or live Yamibo transport is unavailable, retirement remains blocked rather than weakening the acceptance criteria.
