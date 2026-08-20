# Repository Instructions

## OpenSpec isolation

- Never merge any file or change under `openspec/` into the `main` branch.
- Before merging into `main`, run `git diff --name-only main...HEAD -- openspec/` and require empty output.
- If a feature branch contains both implementation and `openspec/` changes, exclude the `openspec/` changes from the commits or history merged into `main`.

## Branch naming isolation

- Before creating any branch, read [Branch Naming Rule Document](dev-docs/branch-naming.md).
- Do not prefix repository branch names with `codex/`; branch names must start directly with the version/lineage pattern defined in the branch naming document.

## Main branch integration

- Integrate changes from any other branch into `main` only through a pull request; do not merge locally or push directly to `main` unless the user explicitly authorizes that specific exception.
- Treat the PR as a checkpoint. Its description must record the problem and root cause, implementation and design decisions, user-visible behavior, verification evidence, and known risks, limits, or rollback notes.
- Before opening or merging the PR, enforce the OpenSpec isolation guard above. Merge only after required checks pass, and prefer a merge commit to preserve branch lineage unless the user requests another strategy.

## Git identity guard

- Before every commit, run `python .github/scripts/validate_git_name.py` from the project root.
- Continue with the commit only when the validator exits successfully. Do not duplicate its identity prompt; its repository-local saved choice is authoritative.
- The validator script is located at [`.github/scripts/validate_git_name.py`](.github/scripts/validate_git_name.py).
