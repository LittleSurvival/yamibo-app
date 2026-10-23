# Repository Instructions

## OpenSpec isolation

- Keep `/openspec/` in the repository-local exclude file returned by `git rev-parse --git-path info/exclude`. Unlike `.gitignore`, this rule survives switching to older branches; ensure it is present before switching branches. Local exclude rules are not included in commits and must be set up per clone.
- Ignore rules do not affect files already tracked by an older branch. Remove such files from that branch's index with `git rm --cached` while preserving the local files before including that branch in any integration.
- Never merge any file or change under `openspec/` into the `main` branch.
- Before merging into `main`, run `git diff --name-only main...HEAD -- openspec/` and require empty output.
- If a feature branch contains both implementation and `openspec/` changes, exclude the `openspec/` changes from the commits or history merged into `main`.

## Branch naming isolation

- Before creating any branch, read [Branch Naming Rule Document](dev-docs/branch-naming.md).
- Do not prefix repository branch names with `codex/`; branch names must start directly with the version/lineage pattern defined in the branch naming document.

## Main branch integration

- Integrate changes from any other branch into `main` only through a pull request; do not merge locally or push directly to `main` unless the user explicitly authorizes that specific exception.
- Release-preparation changes limited to Gradle app version fields, release/update metadata (including `update/manifest.json` and its generated `update/stable.json`), and `.changelog` files may be committed and pushed directly on `main` without a pull request. This exception does not cover application behavior, build logic, or release workflow changes.
- Treat the PR as a checkpoint. Its description must record the problem and root cause, implementation and design decisions, user-visible behavior, verification evidence, and known risks, limits, or rollback notes.
- Before opening or merging the PR, enforce the OpenSpec isolation guard above. Merge only after required checks pass, and prefer a merge commit to preserve branch lineage unless the user requests another strategy.

## Test isolation

- Permanent Apple validation infrastructure is an explicit exception: `.github/workflows/apple-validate.yml` and its documentation may be merged through a PR. Validation scripts use `.github/scripts/apple-validate/<full-branch-name>-<NN>.sh` and remain on validation branches with their supporting files, test sources, fixtures, QA startup adapters, and one-off workflows. Use the single manual workflow with a validation ref rather than adding a workflow per experiment; see [Apple emulator validation](dev-docs/apple-emulator-validation.md).
- Keep test sources, fixtures, QA receivers, simulator scripts, test-only dependencies, and test run configurations off `main` and production PR branches.
- `archived-tests` is the permanent archive name, exempt from version-based branch naming. Never merge it into `main`, and never merge `main` into it merely to update production code; doing so can remove archived tests.
- Use a separate validation branch/worktree for test commits. Build the production PR branch from current `main` and cherry-pick only production commits. Do not hide test commits by deleting their files in a later PR commit.
- Keep validation evidence in PR descriptions, including the production commit, validation commit/branch, commands, outcomes, and limitations. Do not treat the absence of tests on `main` as a passing test run.
- Before delivering to `main`, run `python .github/scripts/validate_test_isolation.py --base origin/main`. See [Test isolation](dev-docs/test-isolation.md) for recovery and archive maintenance.

## Git identity guard

- Before every commit, run `python .github/scripts/validate_git_name.py` from the project root.
- Continue with the commit only when the validator exits successfully. Do not duplicate its identity prompt; its repository-local saved choice is authoritative.
- The validator script is located at [`.github/scripts/validate_git_name.py`](.github/scripts/validate_git_name.py).
