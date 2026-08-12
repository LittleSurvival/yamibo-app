# Repository Instructions

## OpenSpec isolation

- Never merge any file or change under `openspec/` into the `main` branch.
- Before merging into `main`, run `git diff --name-only main...HEAD -- openspec/` and require empty output.
- If a feature branch contains both implementation and `openspec/` changes, exclude the `openspec/` changes from the commits or history merged into `main`.

## Branch naming isolation

- Before creating any branch, read [Branch Naming Rule Document](dev-docs/branch-naming.md).

## Git identity guard

- Before every commit, run `python .github/scripts/validate_git_name.py` from the project root.
- Continue with the commit only when the validator exits successfully. Do not duplicate its identity prompt; its repository-local saved choice is authoritative.
- The validator script is located at [`.github/scripts/validate_git_name.py`](.github/scripts/validate_git_name.py).
