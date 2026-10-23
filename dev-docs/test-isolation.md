# Test isolation

`main` contains production code, build/release tooling, and repository policy
checks. Test sources, fixtures, QA receivers, simulator tools, their dependencies,
and their run configurations live on validation branches or `archived-tests`.

The permanent [Apple validation dispatcher](apple-emulator-validation.md) is an
explicit infrastructure exception: its one manual workflow, shared dispatcher,
Kotlin compilation baseline, and documentation may ship through a PR. Feature
profiles and all test sources, fixtures, and application QA wiring remain on the
selected validation branch. Do not merge a feature-specific workflow into `main`.

## Existing archive

The permanent branch is **`archived-tests`**, independent of release versions.
Its initial snapshot is commit `6aa06dc72877a25d6e3f2d8b4b651e1133996e71`.
It preserves the complete project needed to run the archived tests, including
146 test source files, four Python test scripts, three Android QA receivers,
performance/WAF tools, and the original build configuration.

This is a historical runnable snapshot, not a guarantee that old tests validate
current production code. The snapshot's last Android verification ran 712 tests
with no failures, errors, or skips. Its debug APK also built successfully.

To inspect or run it without changing the production checkout:

```text
git fetch origin archived-tests
git worktree add --detach ../yamibo-tests origin/archived-tests
```

Configure that worktree's local SDK/signing settings before building. Use the
same rule for OpenSpec exclusion in every clone. Never merge `archived-tests`
into `main`. Do not merge `main` into the archive: production deletion commits
can remove tests from the archive.

## New changes

1. Create the production branch from current `main`, following branch naming
   rules. Commit product changes separately from test/QA changes.
2. Create a validation branch/worktree from the production commit. Add or restore
   tests and any required test dependencies there, then run validation.
3. Record the exact production commit and validation commit, commands, outcomes,
   and limitations. Keep the validation branch remotely so the evidence remains
   reproducible.
4. Open the PR from the production branch. If development mixed product and test
   commits, create a clean delivery branch from `main` and cherry-pick only the
   production commits. For mixed individual commits, split out production
   changes first; merely deleting tests in a later commit is insufficient.
5. Run the isolation check after staging and before delivery:

   ```text
   python .github/scripts/validate_test_isolation.py --base origin/main
   ```

The GitHub workflow checks production trees and every commit introduced by a PR.
It detects conventional test paths, the archived QA entry points, and common
test-only build wiring. Extend its rules if a new test framework uses different
conventions. It is a repository policy check, not a replacement for validation.
Tests are intentionally not ignored globally: accidental staging should be
visible and rejected, while validation branches must still be able to commit them.

## Maintaining the archive

Keep `archived-tests` append-only: no force-push and no version-specific rename.
When saving newer tests there, work in an archive worktree and copy/cherry-pick
the intended test changes with their necessary production/dependency updates.
Resolve changes there deliberately so historical tests are not accidentally
deleted. Record the validated production SHA in the archive commit message.
Keep feature validation branches when their runtime differs from the archive.

Only create a fresh worktree from `archived-tests` to run an old snapshot.
Restoring tests to a newer validation branch also requires adapting the test
dependencies/build configuration; do not overwrite current production build
files wholesale with historical versions.

## Scope of the 2026-10-20 cleanup

The user authorized direct processing on `main` for this cleanup only. Normal
future integrations still require PRs. The cleanup removes the archived test/QA
files, their debug manifest entries, WAF debug tasks, and test-only dependencies.
Normal debug/release app builds and production WAF handling remain available.
The removed `debugWafEnvironment` setting no longer enables a proxy in main.

Removing tracked files does not erase their Git history or the remote archive,
and does not remove ignored build outputs or old local emulator data. Those
require separate workspace cleanup.
